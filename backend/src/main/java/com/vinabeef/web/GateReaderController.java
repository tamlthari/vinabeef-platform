package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.security.RbacScopeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/readers")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@SuppressWarnings("unchecked")
public class GateReaderController {

    private final JdbcTemplate jdbcTemplate;
    private final RbacScopeService rbacScopeService;

    @Data
    public static class GateReaderRequest {
        private String gateId;
        private String barnId;
        private String label;
        private String readerName;
        private String directionMode;
        private String firmwareVersion;
    }

    @GetMapping
    public ResponseEntity listReaders(@RequestParam(required = false, defaultValue = "") String q) {
        var scope = rbacScopeService.getCurrentScope();
        String pattern = "%" + (q != null ? q.trim().toLowerCase() : "") + "%";

        StringBuilder sql = new StringBuilder("""
            SELECT 
                g.gate_id,
                g.barn_id,
                g.label,
                g.reader_name,
                g.direction_mode,
                COALESCE(g.firmware_version, '1.58.1998') AS firmware_version,
                COALESCE(g.status, 'ONLINE') AS status,
                b.code AS barn_code,
                f.farm_id,
                f.name AS farm_name,
                (SELECT MAX(re.observed_at) FROM rfid_read_events re WHERE re.reader_name = g.reader_name) AS last_seen,
                (SELECT COUNT(*) FROM movements m WHERE m.gate_id = g.gate_id) AS passage_count
            FROM gates g
            LEFT JOIN barns b ON g.barn_id = b.barn_id
            LEFT JOIN farms f ON b.farm_id = f.farm_id
            WHERE (
                LOWER(g.reader_name) LIKE ?
                OR LOWER(g.label) LIKE ?
                OR LOWER(g.gate_id) LIKE ?
                OR LOWER(COALESCE(b.code, '')) LIKE ?
                OR LOWER(COALESCE(f.name, '')) LIKE ?
            )
        """);

        List params = new ArrayList();
        params.add(pattern);
        params.add(pattern);
        params.add(pattern);
        params.add(pattern);
        params.add(pattern);

        if (scope.isGlobal()) {
            // SYSTEM_ADMIN and EXECUTIVE see all 125 sensors across all farms
        } else if (scope.isFarmScoped() && scope.farmId() != null) {
            sql.append(" AND b.farm_id = ? ");
            params.add(scope.farmId());
        } else if (scope.isBarnScoped()) {
            if (scope.barnIds().isEmpty()) {
                return ResponseEntity.ok(List.of());
            }
            String placeholders = String.join(",", Collections.nCopies(scope.barnIds().size(), "?"));
            sql.append(" AND g.barn_id IN (").append(placeholders).append(") ");
            params.addAll(scope.barnIds());
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User '" + scope.username() + "' is not authorized to view Gate Readers."));
        }

        sql.append(" ORDER BY g.reader_name ASC ");

        var readers = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        return ResponseEntity.ok(readers);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER')")
    @Audited(action = "REGISTER_GATE_READER", targetType = "GATE")
    @Transactional
    public ResponseEntity registerReader(@RequestBody GateReaderRequest req) {
        var scope = rbacScopeService.getCurrentScope();

        if (req.getReaderName() == null || req.getReaderName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Hardware Reader Name (e.g. DrEBE6) is required"));
        }

        if (req.getBarnId() != null && !req.getBarnId().isBlank()
                && !rbacScopeService.canAccessBarnId(scope, req.getBarnId().trim())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User '" + scope.username() + "' is not authorized to provision Gate Readers in Barn '" + req.getBarnId() + "'."));
        }

        String gateId = (req.getGateId() != null && !req.getGateId().isBlank())
                ? req.getGateId().trim()
                : "GATE-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        jdbcTemplate.update("""
            INSERT INTO gates (gate_id, barn_id, label, reader_name, direction_mode, firmware_version, status)
            VALUES (?, ?, ?, ?, ?, ?, 'ONLINE')
        """, gateId, req.getBarnId(), req.getLabel(), req.getReaderName().trim(),
                req.getDirectionMode() != null ? req.getDirectionMode() : "BIDIRECTIONAL",
                req.getFirmwareVersion() != null && !req.getFirmwareVersion().isBlank() ? req.getFirmwareVersion() : "1.58.1998");

        return ResponseEntity.ok(Map.of("status", "REGISTERED", "gateId", gateId));
    }

    @GetMapping("/{gateId}")
    public ResponseEntity getReaderDetails(@PathVariable String gateId) {
        var scope = rbacScopeService.getCurrentScope();

        var readers = jdbcTemplate.queryForList("""
            SELECT 
                g.gate_id,
                g.barn_id,
                g.label,
                g.reader_name,
                g.direction_mode,
                COALESCE(g.firmware_version, '1.58.1998') AS firmware_version,
                COALESCE(g.status, 'ONLINE') AS status,
                b.code AS barn_code,
                f.farm_id,
                f.name AS farm_name,
                (SELECT MAX(re.observed_at) FROM rfid_read_events re WHERE re.reader_name = g.reader_name) AS last_seen
            FROM gates g
            LEFT JOIN barns b ON g.barn_id = b.barn_id
            LEFT JOIN farms f ON b.farm_id = f.farm_id
            WHERE g.gate_id = ?
        """, gateId);

        if (readers.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map reader = new LinkedHashMap(readers.get(0));
        String barnId = reader.get("barn_id") != null ? String.valueOf(reader.get("barn_id")) : null;
        String farmId = reader.get("farm_id") != null ? String.valueOf(reader.get("farm_id")) : null;

        if (!scope.canAccessBarn(barnId, farmId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User '" + scope.username() + "' is not authorized to access Gate Reader '" + gateId + "'."));
        }

        String readerName = (String) reader.get("reader_name");

        var transactions = jdbcTemplate.queryForList("""
            SELECT 
                re.event_id,
                re.event_key,
                re.card_uuid,
                re.user_name,
                re.gate_action,
                re.observed_at,
                m.movement_id,
                m.animal_id,
                m.direction
            FROM rfid_read_events re
            LEFT JOIN movements m ON re.event_id = m.source_event_id
            WHERE re.reader_name = ?
            ORDER BY re.observed_at DESC
            LIMIT 50
        """, readerName);

        return ResponseEntity.ok(Map.of(
                "reader", reader,
                "transactions", transactions
        ));
    }

    @PutMapping("/{gateId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER')")
    @Audited(action = "UPDATE_GATE_READER", targetType = "GATE")
    @Transactional
    public ResponseEntity updateReader(@PathVariable String gateId, @RequestBody GateReaderRequest req) {
        var scope = rbacScopeService.getCurrentScope();

        var existing = jdbcTemplate.queryForList("SELECT barn_id FROM gates WHERE gate_id = ?", gateId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String currentBarnId = existing.get(0).get("barn_id") != null
                ? String.valueOf(existing.get(0).get("barn_id")) : null;

        if (!rbacScopeService.canAccessBarnId(scope, currentBarnId)
                || (req.getBarnId() != null && !req.getBarnId().isBlank()
                    && !rbacScopeService.canAccessBarnId(scope, req.getBarnId().trim()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User '" + scope.username() + "' is not authorized to mutate Gate Reader '" + gateId + "'."));
        }

        jdbcTemplate.update("""
            UPDATE gates 
            SET barn_id = ?, label = ?, direction_mode = ?, firmware_version = ?
            WHERE gate_id = ?
        """, req.getBarnId(), req.getLabel(), req.getDirectionMode(), req.getFirmwareVersion(), gateId);

        return ResponseEntity.ok(Map.of("status", "UPDATED", "gateId", gateId));
    }
}