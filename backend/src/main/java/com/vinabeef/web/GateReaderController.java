package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.facilities.GateDirectionMode;
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

    /**
     * Used for registering and for editing. On an edit a field that is left out (null) keeps its current value, so a
     * client that only sends {@code {"label": "North gate"}} changes the label and nothing else.
     */
    @Data
    public static class GateReaderRequest {
        private String gateId;
        private String barnId;
        private String label;
        private String readerName;
        /**
         * Optional. Name of the OUTER reader of the same gate. When given, {@code readerName} is the INNER
         * reader and the gate decides direction from the order in which the two readers see a tag.
         * On an edit: omitted keeps the current outer reader, an empty string removes it.
         */
        private String outerReaderName;
        /** BIDIRECTIONAL, IN_ONLY (every read is an animal returning) or OUT_ONLY (every read is an animal leaving). */
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
                (SELECT gr.reader_name FROM gate_readers gr WHERE gr.gate_id = g.gate_id AND gr.side = 'OUTER') AS outer_reader_name,
                (SELECT MAX(re.observed_at) FROM rfid_read_events re
                  WHERE re.reader_name IN (SELECT g.reader_name
                                           UNION SELECT gr.reader_name FROM gate_readers gr WHERE gr.gate_id = g.gate_id)) AS last_seen,
                (SELECT COUNT(*) FROM movements m WHERE m.gate_id = g.gate_id) AS passage_count
            FROM gates g
            LEFT JOIN barns b ON g.barn_id = b.barn_id
            LEFT JOIN farms f ON b.farm_id = f.farm_id
            WHERE g.retired_at IS NULL
            AND (
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
        if (req.getBarnId() == null || req.getBarnId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Assigned barn is required"));
        }
        if (req.getLabel() == null || req.getLabel().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Friendly gate label is required"));
        }

        String barnId = req.getBarnId().trim();
        if (!rbacScopeService.canAccessBarnId(scope, barnId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User '" + scope.username() + "' is not authorized to provision Gate Readers in Barn '" + barnId + "'."));
        }
        if (!barnExists(barnId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Barn '" + barnId + "' does not exist."));
        }

        var plan = GateReaderPlan.plan(null, null, null, req.getReaderName(), req.getOuterReaderName(), req.getDirectionMode());
        if (!plan.ok()) {
            return ResponseEntity.status(plan.status()).body(Map.of("error", plan.error()));
        }
        var target = plan.target();

        String gateId = (req.getGateId() != null && !req.getGateId().isBlank())
                ? req.getGateId().trim()
                : "GATE-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Integer sameId = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gates WHERE gate_id = ?", Integer.class, gateId);
        if (sameId != null && sameId > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Gate '" + gateId + "' already exists."));
        }

        for (String name : target.outer() == null ? List.of(target.primary()) : List.of(target.primary(), target.outer())) {
            if (readerNameTaken(name, null)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Reader '" + name + "' is already registered to a gate."));
            }
        }

        jdbcTemplate.update("""
            INSERT INTO gates (gate_id, barn_id, label, reader_name, direction_mode, firmware_version, status)
            VALUES (?, ?, ?, ?, ?, ?, 'ONLINE')
        """, gateId, barnId, req.getLabel().trim(), target.primary(), target.mode().name(),
                req.getFirmwareVersion() != null && !req.getFirmwareVersion().isBlank() ? req.getFirmwareVersion().trim() : "1.58.1998");

        if (target.outer() != null) {
            jdbcTemplate.update("INSERT INTO gate_readers (reader_name, gate_id, side) VALUES (?, ?, 'INNER')", target.primary(), gateId);
            jdbcTemplate.update("INSERT INTO gate_readers (reader_name, gate_id, side) VALUES (?, ?, 'OUTER')", target.outer(), gateId);
        }

        return ResponseEntity.ok(Map.of("status", "REGISTERED", "gateId", gateId, "directionMode", target.mode().name()));
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
                (SELECT gr.reader_name FROM gate_readers gr WHERE gr.gate_id = g.gate_id AND gr.side = 'OUTER') AS outer_reader_name,
                (SELECT MAX(re.observed_at) FROM rfid_read_events re
                  WHERE re.reader_name IN (SELECT g.reader_name
                                           UNION SELECT gr.reader_name FROM gate_readers gr WHERE gr.gate_id = g.gate_id)) AS last_seen,
                (SELECT COUNT(*) FROM movements m WHERE m.gate_id = g.gate_id) AS passage_count
            FROM gates g
            LEFT JOIN barns b ON g.barn_id = b.barn_id
            LEFT JOIN farms f ON b.farm_id = f.farm_id
            WHERE g.gate_id = ? AND g.retired_at IS NULL
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

        var transactions = jdbcTemplate.queryForList("""
            SELECT
                re.event_id,
                re.event_key,
                re.reader_name,
                re.card_uuid,
                re.user_name,
                re.gate_action,
                re.observed_at,
                m.movement_id,
                m.animal_id,
                m.direction
            FROM rfid_read_events re
            LEFT JOIN movements m ON re.event_id = m.source_event_id
            WHERE re.reader_name IN (SELECT g.reader_name FROM gates g WHERE g.gate_id = ?
                                     UNION SELECT gr.reader_name FROM gate_readers gr WHERE gr.gate_id = ?)
            ORDER BY re.observed_at DESC
            LIMIT 50
        """, gateId, gateId);

        return ResponseEntity.ok(Map.of(
                "reader", reader,
                "transactions", transactions
        ));
    }

    /**
     * Edits a reader. Only the fields that are sent change. The direction mode, the reader names and the
     * inner/outer pair are checked together, so a gate can never end up one-way with two readers.
     */
    @PutMapping("/{gateId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER')")
    @Audited(action = "UPDATE_GATE_READER", targetType = "GATE")
    @Transactional
    public ResponseEntity updateReader(@PathVariable String gateId, @RequestBody GateReaderRequest req) {
        var scope = rbacScopeService.getCurrentScope();

        var existing = jdbcTemplate.queryForList("""
            SELECT barn_id, label, reader_name, direction_mode, firmware_version,
                   (SELECT gr.reader_name FROM gate_readers gr WHERE gr.gate_id = gates.gate_id AND gr.side = 'OUTER') AS outer_reader_name
            FROM gates WHERE gate_id = ? AND retired_at IS NULL
        """, gateId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map row = existing.get(0);
        String currentBarnId = row.get("barn_id") != null ? String.valueOf(row.get("barn_id")) : null;
        String currentPrimary = (String) row.get("reader_name");
        String currentOuter = (String) row.get("outer_reader_name");

        String newBarnId = req.getBarnId() != null && !req.getBarnId().isBlank() ? req.getBarnId().trim() : currentBarnId;
        if (!rbacScopeService.canAccessBarnId(scope, currentBarnId) || !rbacScopeService.canAccessBarnId(scope, newBarnId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User '" + scope.username() + "' is not authorized to mutate Gate Reader '" + gateId + "'."));
        }
        if (!Objects.equals(newBarnId, currentBarnId) && !barnExists(newBarnId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Barn '" + newBarnId + "' does not exist."));
        }

        var plan = GateReaderPlan.plan(currentPrimary, currentOuter,
                GateDirectionMode.parseOrDefault((String) row.get("direction_mode")),
                req.getReaderName(), req.getOuterReaderName(), req.getDirectionMode());
        if (!plan.ok()) {
            return ResponseEntity.status(plan.status()).body(Map.of("error", plan.error()));
        }
        var target = plan.target();

        List<String> newNames = new ArrayList<>();
        if (!target.primary().equals(currentPrimary)) newNames.add(target.primary());
        if (target.outer() != null && !target.outer().equals(currentOuter)) newNames.add(target.outer());
        for (String name : newNames) {
            if (readerNameTaken(name, gateId)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Reader '" + name + "' is already registered to a gate."));
            }
        }

        String newLabel = req.getLabel() != null && !req.getLabel().isBlank() ? req.getLabel().trim() : (String) row.get("label");
        String newFirmware = req.getFirmwareVersion() != null && !req.getFirmwareVersion().isBlank()
                ? req.getFirmwareVersion().trim() : (String) row.get("firmware_version");

        jdbcTemplate.update("""
            UPDATE gates
            SET barn_id = ?, label = ?, reader_name = ?, direction_mode = ?, firmware_version = ?
            WHERE gate_id = ? AND retired_at IS NULL
        """, newBarnId, newLabel, target.primary(), target.mode().name(), newFirmware, gateId);

        if (target.pairChanged()) {
            // Readers that stay on the same side keep their Safetrust details; the rest are replaced.
            // Unpaired sightings belong to the old pair and can no longer be matched.
            if (target.outer() == null) {
                jdbcTemplate.update("DELETE FROM gate_readers WHERE gate_id = ?", gateId);
            } else {
                jdbcTemplate.update("""
                    DELETE FROM gate_readers
                    WHERE gate_id = ?
                      AND NOT ((reader_name = ? AND side = 'INNER') OR (reader_name = ? AND side = 'OUTER'))
                """, gateId, target.primary(), target.outer());
                jdbcTemplate.update("INSERT INTO gate_readers (reader_name, gate_id, side) VALUES (?, ?, 'INNER') ON CONFLICT (reader_name) DO NOTHING",
                        target.primary(), gateId);
                jdbcTemplate.update("INSERT INTO gate_readers (reader_name, gate_id, side) VALUES (?, ?, 'OUTER') ON CONFLICT (reader_name) DO NOTHING",
                        target.outer(), gateId);
            }
            jdbcTemplate.update("DELETE FROM gate_crossing_candidates WHERE gate_id = ?", gateId);
        }

        return ResponseEntity.ok(Map.of("status", "UPDATED", "gateId", gateId, "directionMode", target.mode().name()));
    }

    /**
     * Removes a reader from the farm. A reader that never recorded a passage is deleted. A reader with history is
     * retired instead: its movements keep pointing at it (traceability), it disappears from the lists, its name
     * can be registered again, and its reads raise the "unregistered reader" alert from now on.
     */
    @DeleteMapping("/{gateId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER')")
    @Audited(action = "DELETE_GATE_READER", targetType = "GATE")
    @Transactional
    public ResponseEntity deleteReader(@PathVariable String gateId) {
        var scope = rbacScopeService.getCurrentScope();

        var existing = jdbcTemplate.queryForList("SELECT barn_id FROM gates WHERE gate_id = ? AND retired_at IS NULL", gateId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String barnId = existing.get(0).get("barn_id") != null ? String.valueOf(existing.get(0).get("barn_id")) : null;
        if (!rbacScopeService.canAccessBarnId(scope, barnId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User '" + scope.username() + "' is not authorized to remove Gate Reader '" + gateId + "'."));
        }

        Integer passages = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM movements WHERE gate_id = ?", Integer.class, gateId);
        if (passages == null || passages == 0) {
            jdbcTemplate.update("DELETE FROM gate_crossing_candidates WHERE gate_id = ?", gateId);
            jdbcTemplate.update("DELETE FROM gate_readers WHERE gate_id = ?", gateId);
            jdbcTemplate.update("DELETE FROM gates WHERE gate_id = ?", gateId);
            return ResponseEntity.ok(Map.of("status", "DELETED", "gateId", gateId));
        }

        String actor = scope.username() != null ? scope.username() : "unknown";
        jdbcTemplate.update("""
            UPDATE gates SET retired_at = CURRENT_TIMESTAMP, retired_by = ?, status = 'RETIRED'
            WHERE gate_id = ? AND retired_at IS NULL
        """, actor, gateId);
        jdbcTemplate.update("DELETE FROM gate_crossing_candidates WHERE gate_id = ?", gateId);
        jdbcTemplate.update("DELETE FROM gate_readers WHERE gate_id = ?", gateId);
        return ResponseEntity.ok(Map.of(
                "status", "RETIRED",
                "gateId", gateId,
                "passages", passages,
                "message", "The reader has " + passages + " recorded passage(s), so it was retired instead of deleted. "
                        + "Its history is kept."));
    }

    private boolean barnExists(String barnId) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM barns WHERE barn_id = ?", Integer.class, barnId);
        return n != null && n > 0;
    }

    /** True when another active gate already uses this reader name. {@code exceptGateId} is the gate being edited. */
    private boolean readerNameTaken(String readerName, String exceptGateId) {
        String except = exceptGateId != null ? exceptGateId : "";
        Integer used = jdbcTemplate.queryForObject("""
            SELECT (SELECT COUNT(*) FROM gates WHERE reader_name = ? AND retired_at IS NULL AND gate_id <> ?)
                 + (SELECT COUNT(*) FROM gate_readers WHERE reader_name = ? AND gate_id <> ?)
        """, Integer.class, readerName, except, readerName, except);
        return used != null && used > 0;
    }
}
