package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/management")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ManagementController {

    private final JdbcTemplate jdbcTemplate;
    private final ReportClock clock;

    @Data
    public static class TransferRequest {
        private String animalId;
        private String targetBarnId;
        private String reason;
    }

    /**
     * Approves an animal transfer.
     * Enforces that the actor is a FARM_MANAGER assigned to the target farm[cite: 10, 40].
     */
    @PostMapping("/farms/{farmId}/transfers")
    @PreAuthorize("hasRole('FARM_MANAGER') and @securityService.isFarmAssigned(authentication, #farmId)")
    @Audited(action = "APPROVE_TRANSFER", targetType = "CATTLE_TRANSFER")
    public ResponseEntity<?> approveAnimalTransfer(@PathVariable String farmId,
                                                   @RequestBody TransferRequest request,
                                                   Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "status", "TRANSFER_APPROVED",
                "farmId", farmId,
                "animalId", request.getAnimalId(),
                "approvedBy", auth.getName()
        ));
    }

    /**
     * Reports on a specific farm's movement count and barn status.
     * Accessible by EXECUTIVES (all farms) or FARM_MANAGERS assigned to this farm[cite: 10, 40].
     */
    @GetMapping("/farms/{farmId}/report")
    @PreAuthorize("hasRole('EXECUTIVE') or (hasRole('FARM_MANAGER') and @securityService.isFarmAssigned(authentication, #farmId))")
    @Audited(action = "GENERATE_FARM_REPORT", targetType = "REPORT")
    public ResponseEntity<?> getFarmReport(@PathVariable String farmId) {
        List<Map<String, Object>> barns = jdbcTemplate.queryForList("""
            SELECT b.code, b.capacity, c.in_barn_count AS current_headcount, c.assigned_count,
                   c.out_pasture_count, COUNT(m.movement_id) as total_movements
            FROM barns b
            JOIN v_barn_counts c ON c.barn_id = b.barn_id
            LEFT JOIN gates g ON b.barn_id = g.barn_id
            LEFT JOIN movements m ON g.gate_id = m.gate_id
            WHERE b.farm_id = ?
            GROUP BY b.barn_id, b.code, b.capacity, c.in_barn_count, c.assigned_count, c.out_pasture_count
        """, farmId);

        return ResponseEntity.ok(Map.of(
                "farmId", farmId,
                "barnData", barns
        ));
    }

    /**
     * Cross-farm executive summary.
     * Restricted to EXECUTIVE role[cite: 10, 40].
     */
    @GetMapping("/executive/summary")
    @PreAuthorize("hasAnyRole('EXECUTIVE', 'SYSTEM_ADMIN')")
    @Audited(action = "VIEW_EXECUTIVE_SUMMARY", targetType = "EXECUTIVE_REPORT")
    public ResponseEntity<?> getExecutiveSummary() {
        List<Map<String, Object>> summary = jdbcTemplate.queryForList("""
            SELECT f.name as farm_name, 
                   COUNT(DISTINCT b.barn_id) as total_barns,
                   COALESCE(SUM(c.assigned_count), 0) as total_cattle,
                   COALESCE(SUM(c.in_barn_count), 0) as in_barn,
                   COALESCE(SUM(c.out_pasture_count), 0) as out_pasture
            FROM farms f
            LEFT JOIN barns b ON f.farm_id = b.farm_id
            LEFT JOIN v_barn_counts c ON c.barn_id = b.barn_id
            GROUP BY f.farm_id, f.name
        """);

        return ResponseEntity.ok(summary);
    }

    /**
     * Audit log viewer: filtered and paged. Restricted to SYSTEM_ADMIN per ADR-006.
     * Filters are optional and combine with AND. Dates are local days or minutes (see {@link ReportWindow}).
     */
    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<?> getAuditLogs(
            @RequestParam(required = false, defaultValue = "") String actor,
            @RequestParam(required = false, defaultValue = "") String action,
            @RequestParam(required = false, defaultValue = "") String target,
            @RequestParam(required = false, defaultValue = "") String outcome,
            @RequestParam(required = false, defaultValue = "") String from,
            @RequestParam(required = false, defaultValue = "") String to,
            @RequestParam(required = false, defaultValue = "7d") String preset,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "25") int size) {
        ReportWindow.Window window;
        try {
            window = ReportWindow.resolve(preset, from, to, clock.zone(), clock.now());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        StringBuilder where = new StringBuilder(" WHERE event_at >= ? AND event_at < ? ");
        List<Object> params = new ArrayList<>();
        params.add(Timestamp.from(window.fromInclusive()));
        params.add(Timestamp.from(window.toExclusive()));
        if (!actor.isBlank()) {
            where.append(" AND LOWER(actor_id) LIKE ? ");
            params.add("%" + actor.trim().toLowerCase() + "%");
        }
        if (!action.isBlank()) {
            where.append(" AND action = ? ");
            params.add(action.trim());
        }
        if (!target.isBlank()) {
            where.append(" AND target = ? ");
            params.add(target.trim());
        }
        if (!outcome.isBlank()) {
            where.append(" AND outcome = ? ");
            params.add(outcome.trim().toUpperCase());
        }

        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_entries" + where, Long.class, params.toArray());

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize);
        pageParams.add((safePage - 1) * safeSize);
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
            SELECT audit_id, actor_id, action, target, outcome, details::text AS details, event_at
            FROM audit_entries
        """ + where + " ORDER BY event_at DESC, audit_id LIMIT ? OFFSET ? ", pageParams.toArray());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", total != null ? total : 0);
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("periodLabel", window.label());
        // values for the filter drop-downs, taken from what was really logged
        body.put("actions", jdbcTemplate.queryForList("SELECT DISTINCT action FROM audit_entries ORDER BY action", String.class));
        body.put("targets", jdbcTemplate.queryForList("SELECT DISTINCT target FROM audit_entries ORDER BY target", String.class));
        return ResponseEntity.ok(body);
    }
}
