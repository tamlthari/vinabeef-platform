package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.facilities.GateDirectionMode;
import com.vinabeef.security.BarnAccessPolicy;
import com.vinabeef.security.CurrentScopeProvider;
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
    private final CurrentScopeProvider currentScope;
    private final BarnAccessPolicy barnAccess;
    private final ReportClock clock;

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
        /** Edit only. Reads at the same reader closer than this many milliseconds are one tap; omitted keeps the current value. */
        private Integer repeatDebounceMs;
    }

    @GetMapping
    public ResponseEntity listReaders(@RequestParam(required = false, defaultValue = "") String q,
                                      @RequestParam(required = false, defaultValue = "") String sort,
                                      @RequestParam(required = false, defaultValue = "") String dir,
                                      @RequestParam(required = false, defaultValue = "") String farmId,
                                      @RequestParam(required = false, defaultValue = "") String barnId,
                                      @RequestParam(required = false, defaultValue = "") String direction,
                                      @RequestParam(required = false, defaultValue = "") String pairing,
                                      @RequestParam(required = false, defaultValue = "") String activity,
                                      @RequestParam(required = false, defaultValue = "") String passes,
                                      @RequestParam(required = false, defaultValue = "1") int page,
                                      @RequestParam(required = false, defaultValue = "100") int size) {
        var scope = currentScope.getCurrentScope();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);

        ReportQueries.Scope visible;
        if (scope.isGlobal()) {
            visible = ReportQueries.Scope.ALL;   // SYSTEM_ADMIN and EXECUTIVE see every reader on every farm
        } else if (scope.isFarmScoped() && scope.farmId() != null) {
            visible = ReportQueries.Scope.farm(scope.farmId());
        } else if (scope.isBarnScoped()) {
            visible = ReportQueries.Scope.barns(scope.barnIds());
            if (visible.seesNothing()) {
                return ResponseEntity.ok().header("X-Total-Count", "0")
                        .header("Access-Control-Expose-Headers", "X-Total-Count").body(List.of());
            }
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User '" + scope.username() + "' is not authorized to view Gate Readers."));
        }

        var today = clock.today();
        // the column filters only narrow what the person may see: their scope is applied first, inside the statement
        var filter = ListQueries.ReaderFilter.parse(farmId, barnId, direction, pairing, activity, passes);
        var now = clock.now();
        var count = ListQueries.readerCount(visible, q, filter, today.fromInclusive(), today.toExclusive(), now);
        Long total = jdbcTemplate.queryForObject(count.sql(), Long.class, count.params().toArray());
        var query = ListQueries.readersPage(visible, q, filter, sort, dir, today.fromInclusive(), today.toExclusive(), now,
                safeSize, (safePage - 1) * safeSize);
        var readers = jdbcTemplate.queryForList(query.sql(), query.params().toArray());
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(total != null ? total : readers.size()))
                .header("X-Sort-Key", SortSpec.effectiveKey(sort, ListQueries.READER_SORT, "created"))
                .header("X-Sort-Dir", SortSpec.effectiveDir(sort, dir, ListQueries.READER_SORT, "created", "desc"))
                .header("Access-Control-Expose-Headers", "X-Total-Count, X-Sort-Key, X-Sort-Dir")
                .body(readers);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER')")
    @Audited(action = "REGISTER_GATE_READER", targetType = "GATE")
    @Transactional
    public ResponseEntity registerReader(@RequestBody GateReaderRequest req) {
        var scope = currentScope.getCurrentScope();

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
        if (!barnAccess.canAccessBarnId(scope, barnId)) {
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

        var nameError = checkReaderNames(target, null, true);
        if (nameError != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", nameError));
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "REGISTERED");
        result.put("gateId", gateId);
        result.put("directionMode", target.mode().name());
        addLinkNote(result, target, null);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{gateId}")
    public ResponseEntity getReaderDetails(@PathVariable String gateId) {
        var scope = currentScope.getCurrentScope();

        var readers = jdbcTemplate.queryForList("""
            SELECT
                g.gate_id,
                g.barn_id,
                g.label,
                g.reader_name,
                g.direction_mode,
                g.repeat_debounce_ms,
                COALESCE(g.firmware_version, '1.58.1998') AS firmware_version,
                COALESCE(g.status, 'ONLINE') AS status,
                b.code AS barn_code,
                f.farm_id,
                f.name AS farm_name,
                (SELECT gr.reader_name FROM gate_readers gr WHERE gr.gate_id = g.gate_id AND gr.side = 'OUTER') AS outer_reader_name,
                (SELECT MAX(re.observed_at) FROM rfid_read_events re
                  WHERE re.reader_name IN (SELECT g.reader_name
                                           UNION SELECT gr.reader_name FROM gate_readers gr WHERE gr.gate_id = g.gate_id)) AS last_seen,
                (SELECT gr.gate_id FROM gate_readers gr WHERE gr.reader_name = g.reader_name AND gr.gate_id <> g.gate_id) AS linked_to_gate_id,
                (SELECT gr.side FROM gate_readers gr WHERE gr.reader_name = g.reader_name AND gr.gate_id <> g.gate_id) AS linked_side,
                (SELECT lg.label FROM gate_readers gr JOIN gates lg ON lg.gate_id = gr.gate_id
                  WHERE gr.reader_name = g.reader_name AND gr.gate_id <> g.gate_id) AS linked_to_label,
                COALESCE(mc.in_count, 0)  AS in_count,
                COALESCE(mc.out_count, 0) AS out_count,
                COALESCE(mc.today_in, 0)  AS today_in,
                COALESCE(mc.today_out, 0) AS today_out,
                COALESCE(mc.in_count, 0) + COALESCE(mc.out_count, 0) AS passage_count
            FROM gates g
            LEFT JOIN barns b ON g.barn_id = b.barn_id
            LEFT JOIN farms f ON b.farm_id = f.farm_id
            LEFT JOIN LATERAL (
                SELECT COUNT(*) FILTER (WHERE m.direction = 'IN')  AS in_count,
                       COUNT(*) FILTER (WHERE m.direction = 'OUT') AS out_count,
                       COUNT(*) FILTER (WHERE m.direction = 'IN'  AND m.occurred_at >= ? AND m.occurred_at < ?) AS today_in,
                       COUNT(*) FILTER (WHERE m.direction = 'OUT' AND m.occurred_at >= ? AND m.occurred_at < ?) AS today_out
                FROM movements m WHERE m.gate_id = g.gate_id
            ) mc ON TRUE
            WHERE g.gate_id = ? AND g.retired_at IS NULL
        """, java.sql.Timestamp.from(clock.today().fromInclusive()), java.sql.Timestamp.from(clock.today().toExclusive()),
                java.sql.Timestamp.from(clock.today().fromInclusive()), java.sql.Timestamp.from(clock.today().toExclusive()), gateId);

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

        // every read with what became of it (passage, first read of a crossing, half crossing, ...): see ReaderReadFeed
        var transactions = jdbcTemplate.queryForList(ReaderReadFeed.SQL, gateId, gateId);

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
        var scope = currentScope.getCurrentScope();

        var existing = jdbcTemplate.queryForList("""
            SELECT barn_id, label, reader_name, direction_mode, firmware_version, repeat_debounce_ms,
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
        if (!barnAccess.canAccessBarnId(scope, currentBarnId) || !barnAccess.canAccessBarnId(scope, newBarnId)) {
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

        var nameError = checkReaderNames(target, gateId, !target.primary().equals(currentPrimary));
        if (nameError != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", nameError));
        }

        String newLabel = req.getLabel() != null && !req.getLabel().isBlank() ? req.getLabel().trim() : (String) row.get("label");
        String newFirmware = req.getFirmwareVersion() != null && !req.getFirmwareVersion().isBlank()
                ? req.getFirmwareVersion().trim() : (String) row.get("firmware_version");

        var timing = GatePairingSettings.resolve(
                row.get("repeat_debounce_ms") instanceof Number d ? d.intValue() : GatePairingSettings.DEFAULT_REPEAT_DEBOUNCE_MS,
                req.getRepeatDebounceMs());
        if (!timing.ok()) {
            return ResponseEntity.badRequest().body(Map.of("error", timing.error()));
        }

        jdbcTemplate.update("""
            UPDATE gates
            SET barn_id = ?, label = ?, reader_name = ?, direction_mode = ?, firmware_version = ?,
                repeat_debounce_ms = ?
            WHERE gate_id = ? AND retired_at IS NULL
        """, newBarnId, newLabel, target.primary(), target.mode().name(), newFirmware,
                timing.repeatDebounceMs(), gateId);

        if (target.pairChanged()) {
            // Readers that stay on the same side keep their Safetrust details; the rest are replaced.
            // A half crossing belongs to the old pair: it stays in the history, but no read of the new pair can
            // complete it any more.
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
            jdbcTemplate.update("UPDATE movements SET reader_side = NULL WHERE gate_id = ? AND crossing_status = 'HALF_CROSSING'", gateId);
            jdbcTemplate.update("DELETE FROM gate_crossing_candidates WHERE gate_id = ?", gateId);   // leftovers of the old pairing
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UPDATED");
        result.put("gateId", gateId);
        result.put("directionMode", target.mode().name());
        addLinkNote(result, target, gateId);
        return ResponseEntity.ok(result);
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
        var scope = currentScope.getCurrentScope();

        var existing = jdbcTemplate.queryForList("SELECT barn_id FROM gates WHERE gate_id = ? AND retired_at IS NULL", gateId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String barnId = existing.get(0).get("barn_id") != null ? String.valueOf(existing.get(0).get("barn_id")) : null;
        if (!barnAccess.canAccessBarnId(scope, barnId)) {
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

    /**
     * A reader has two separate roles, and they do not exclude each other:
     * <ul>
     *   <li>its own RECORD: a row in the readers list (label, barn, firmware, last seen). One per reader name.</li>
     *   <li>its place in a PAIR: the inner or outer reader of a bidirectional gate. One per reader name.</li>
     * </ul>
     * So C1C4 can have its own record and also be the outer reader of C0F8's gate. While the pair exists, reads of
     * C1C4 count for the pair's gate (the pair is looked up first); the own record keeps showing the reader's
     * status and applies again when the pair is removed.
     *
     * @return an error message, or null when the names are fine
     */
    private String checkReaderNames(GateReaderPlan.Target target, String gateId, boolean primaryChanged) {
        if (primaryChanged) {
            String record = otherRecordOf(target.primary(), gateId);
            if (record != null) {
                return "Reader '" + target.primary() + "' already has its own record (gate " + record + ").";
            }
        }
        if (target.outer() != null && target.pairChanged()) {
            for (String name : List.of(target.primary(), target.outer())) {
                String pair = otherPairOf(name, gateId);
                if (pair != null) {
                    return "Reader '" + name + "' is already part of the reader pair of gate " + pair + ".";
                }
            }
        }
        return null;
    }

    /** Tells the user when a reader's own record and its place in a pair overlap (allowed, but worth knowing). */
    private void addLinkNote(Map<String, Object> result, GateReaderPlan.Target target, String gateId) {
        if (target.outer() != null) {
            String record = otherRecordOf(target.outer(), gateId);
            if (record != null) {
                result.put("note", "Reader '" + target.outer() + "' also has its own record (gate " + record
                        + "). While it is the outer reader of this gate, its reads count here.");
            }
        } else {
            String pair = otherPairOf(target.primary(), gateId);
            if (pair != null) {
                result.put("note", "Reader '" + target.primary() + "' is part of the reader pair of gate " + pair
                        + ", so its reads count there while that pair exists.");
            }
        }
    }

    /** Gate id of ANOTHER active gate that has this reader as its own record, or null. */
    private String otherRecordOf(String readerName, String exceptGateId) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT gate_id FROM gates WHERE reader_name = ? AND retired_at IS NULL AND gate_id <> ?",
                String.class, readerName, exceptGateId != null ? exceptGateId : "");
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Gate id of ANOTHER gate whose inner/outer pair already uses this reader, or null. */
    private String otherPairOf(String readerName, String exceptGateId) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT gate_id FROM gate_readers WHERE reader_name = ? AND gate_id <> ?",
                String.class, readerName, exceptGateId != null ? exceptGateId : "");
        return ids.isEmpty() ? null : ids.get(0);
    }
}
