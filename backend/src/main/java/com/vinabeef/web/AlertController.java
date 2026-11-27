package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.security.BarnAccessPolicy;
import com.vinabeef.security.CurrentScopeProvider;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@SuppressWarnings("unchecked")
public class AlertController {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentScopeProvider currentScope;
    private final BarnAccessPolicy barnAccess;

    @Data
    public static class ReconcileRequest {
        private String barnId;
        private Integer thresholdMinutes;
    }

    @Data
    public static class ResolveRequest {
        private String note;
    }

    /**
     * One page of the alerts a person may see, newest first, with the total number that match so the page can draw its pager.
     * {@code size} defaults to 100 (what this list always returned) and is at most 100.
     */
    @GetMapping
    public ResponseEntity<?> listAlerts(
            @RequestParam(required = false, defaultValue = "OPEN") String state,
            @RequestParam(required = false, defaultValue = "ALL") String type,
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "100") int size) {

        var scope = currentScope.getCurrentScope();
        ReportQueries.Scope visible = ReportQueries.Scope.of(scope);
        int safePage = Math.max(1, page);
        int safeSize = AlertQueries.pageSize(size);

        if (visible.seesNothing()) {
            return ResponseEntity.ok(Map.of(
                    "alerts", List.of(),
                    "total", 0,
                    "page", safePage,
                    "size", safeSize,
                    "stats", Map.of("openCount", 0, "acknowledgedCount", 0, "missingCount", 0, "unexpectedCount", 0, "wrongBarnCount", 0)
            ));
        }

        var count = AlertQueries.count(visible, state, type, q);
        Long total = jdbcTemplate.queryForObject(count.sql(), Long.class, count.params().toArray());
        var pageQuery = AlertQueries.page(visible, state, type, q, safeSize, (safePage - 1) * safeSize);
        var alerts = jdbcTemplate.queryForList(pageQuery.sql(), pageQuery.params().toArray());

        // the counters at the top of the page ignore the filters but respect who is asking
        StringBuilder scopeClause = new StringBuilder();
        List<Object> scopeParams = new ArrayList<>();
        ReportQueries.scope(scopeClause, scopeParams, visible, "b");
        String baseCountSql = "SELECT COUNT(*) FROM alerts al LEFT JOIN barns b ON al.barn_id = b.barn_id WHERE 1=1 " + scopeClause;
        Object[] sArgs = scopeParams.toArray();

        Integer openCount = jdbcTemplate.queryForObject(
                baseCountSql + " AND al.state = 'OPEN'", Integer.class, sArgs);
        Integer ackCount = jdbcTemplate.queryForObject(
                baseCountSql + " AND al.state = 'ACKNOWLEDGED'", Integer.class, sArgs);
        Integer missingCount = jdbcTemplate.queryForObject(
                baseCountSql + " AND al.type = 'MISSING_CATTLE' AND al.state IN ('OPEN', 'ACKNOWLEDGED')", Integer.class, sArgs);
        Integer unexpectedCount = jdbcTemplate.queryForObject(
                baseCountSql + " AND al.type = 'UNEXPECTED_TAG' AND al.state IN ('OPEN', 'ACKNOWLEDGED')", Integer.class, sArgs);
        Integer wrongBarnCount = jdbcTemplate.queryForObject(
                baseCountSql + " AND al.type = 'WRONG_BARN' AND al.state IN ('OPEN', 'ACKNOWLEDGED')", Integer.class, sArgs);

        return ResponseEntity.ok(Map.of(
                "alerts", alerts,
                "total", total != null ? total : alerts.size(),
                "page", safePage,
                "size", safeSize,
                "stats", Map.of(
                        "openCount", openCount != null ? openCount : 0,
                        "acknowledgedCount", ackCount != null ? ackCount : 0,
                        "missingCount", missingCount != null ? missingCount : 0,
                        "unexpectedCount", unexpectedCount != null ? unexpectedCount : 0,
                        "wrongBarnCount", wrongBarnCount != null ? wrongBarnCount : 0
                )
        ));
    }

    @PostMapping("/reconcile-missing")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "RECONCILE_MISSING_CATTLE", targetType = "BARN")
    @Transactional
    public ResponseEntity<?> reconcileMissingCattle(@RequestBody ReconcileRequest req) {
        if (req.getBarnId() == null || req.getBarnId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please select a barn to run roll-call reconciliation."));
        }

        String barnId = req.getBarnId().trim();
        var scope = currentScope.getCurrentScope();
        if (!barnAccess.canAccessBarnId(scope, barnId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access denied: Barn " + barnId + " is outside your assigned scope."));
        }

        int thresholdMinutes = (req.getThresholdMinutes() != null && req.getThresholdMinutes() >= 0)
                ? req.getThresholdMinutes() : 30;

        List missingAnimalIds = jdbcTemplate.queryForList("""
            SELECT a.animal_id
            FROM animals a
            LEFT JOIN presence_states ps ON a.animal_id = ps.animal_id
            WHERE a.assigned_barn_id = ?
              AND a.lifecycle_status = 'ACTIVE'
              AND COALESCE(ps.presence_status, 'OUT_PASTURE') = 'OUT_PASTURE'
              AND (ps.updated_at IS NULL OR ps.updated_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 minute'))
              AND NOT EXISTS (
                  SELECT 1 FROM alerts al
                  WHERE al.animal_id = a.animal_id
                    AND al.type = 'MISSING_CATTLE'
                    AND al.state IN ('OPEN', 'ACKNOWLEDGED')
              )
            ORDER BY a.animal_id ASC
            LIMIT 50
        """, String.class, barnId, thresholdMinutes);

        for (Object idObj : missingAnimalIds) {
            String animalId = String.valueOf(idObj);
            String alertId = "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            jdbcTemplate.update("""
                INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
                VALUES (?, 'MISSING_CATTLE', 'HIGH', ?, ?, 'OPEN',
                        jsonb_build_object('expectedBarn', ?, 'lastStatus', 'OUT_PASTURE', 'overdueMinutes', ?),
                        CURRENT_TIMESTAMP)
            """, alertId, animalId, barnId, barnId, thresholdMinutes);
        }

        return ResponseEntity.ok(Map.of(
                "status", "COMPLETED",
                "barnId", barnId,
                "flaggedCount", missingAnimalIds.size()
        ));
    }

    @PostMapping("/{alertId}/acknowledge")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "ACKNOWLEDGE_ALERT", targetType = "ALERT")
    @Transactional
    public ResponseEntity<?> acknowledgeAlert(@PathVariable String alertId, Authentication auth) {
        if (!canAccessAlert(alertId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Alert is outside your assigned farm/barn scope."));
        }

        String actor = auth != null ? auth.getName() : "operator";
        int updated = jdbcTemplate.update("""
            UPDATE alerts
            SET state = 'ACKNOWLEDGED',
                acknowledged_by = ?,
                acknowledged_at = CURRENT_TIMESTAMP
            WHERE alert_id = ? AND state = 'OPEN'
        """, actor, alertId);

        if (updated == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Alert is not in OPEN state or does not exist."));
        }
        return ResponseEntity.ok(Map.of("status", "ACKNOWLEDGED", "alertId", alertId));
    }

    @PostMapping("/{alertId}/resolve")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "RESOLVE_ALERT", targetType = "ALERT")
    @Transactional
    public ResponseEntity<?> resolveAlert(@PathVariable String alertId,
                                          @RequestBody(required = false) ResolveRequest req,
                                          Authentication auth) {
        if (!canAccessAlert(alertId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Alert is outside your assigned farm/barn scope."));
        }

        String actor = auth != null ? auth.getName() : "operator";
        String note = (req != null && req.getNote() != null && !req.getNote().isBlank())
                ? req.getNote().trim()
                : "Verified and cleared by field staff";

        int updated = jdbcTemplate.update("""
            UPDATE alerts
            SET state = 'RESOLVED',
                resolved_by = ?,
                resolved_at = CURRENT_TIMESTAMP,
                resolution_note = ?
            WHERE alert_id = ? AND state IN ('OPEN', 'ACKNOWLEDGED')
        """, actor, note, alertId);

        if (updated == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Alert already resolved or not found."));
        }
        return ResponseEntity.ok(Map.of("status", "RESOLVED", "alertId", alertId));
    }

    private boolean canAccessAlert(String alertId) {
        var scope = currentScope.getCurrentScope();
        if (scope.isGlobal()) return true;
        var rows = jdbcTemplate.queryForList("""
            SELECT al.barn_id, b.farm_id
            FROM alerts al
            LEFT JOIN barns b ON al.barn_id = b.barn_id
            WHERE al.alert_id = ?
        """, alertId);
        if (rows.isEmpty()) return false;
        String barnId = (String) rows.get(0).get("barn_id");
        String farmId = (String) rows.get(0).get("farm_id");
        return scope.canAccessBarn(barnId, farmId);
    }
}