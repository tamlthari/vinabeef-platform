package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.security.CurrentScopeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reporting page: herd balance per barn, gate passes (IN / OUT) and the raw RFID reads behind them.
 * All three obey the same scope as the rest of the system and the same time window rules.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'EXECUTIVE', 'FARM_MANAGER')")
public class ReportController {

    private static final int EXPORT_LIMIT = 50_000;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentScopeProvider currentScope;
    private final ReportClock clock;

    /** Per barn: herd, in barn now, at pasture now, never seen, and IN / OUT passes in the period. */
    @GetMapping("/herd-balance")
    public ResponseEntity<?> herdBalance(
            @RequestParam(required = false, defaultValue = "") String farmId,
            @RequestParam(required = false, defaultValue = "today") String preset,
            @RequestParam(required = false, defaultValue = "") String from,
            @RequestParam(required = false, defaultValue = "") String to) {
        ReportWindow.Window window = window(preset, from, to);
        var scope = scope();
        List<Map<String, Object>> rows = scope.seesNothing() ? List.of()
                : run(ReportQueries.herdBalance(scope, farmId, window.fromInclusive(), window.toExclusive()));

        long herd = 0, inBarn = 0, atPasture = 0, unseen = 0, in = 0, out = 0;
        for (Map<String, Object> r : rows) {
            herd += num(r.get("assigned_count"));
            inBarn += num(r.get("in_barn_count"));
            atPasture += num(r.get("out_pasture_count"));
            unseen += num(r.get("unseen_count"));
            in += num(r.get("in_count"));
            out += num(r.get("out_count"));
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("herd", herd);
        totals.put("inBarn", inBarn);
        totals.put("atPasture", atPasture);
        totals.put("unseen", unseen);
        totals.put("passesIn", in);
        totals.put("passesOut", out);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodLabel", window.label());
        body.put("rows", rows);
        body.put("totals", totals);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/passages")
    public ResponseEntity<?> passages(
            @RequestParam(required = false, defaultValue = "") String farmId,
            @RequestParam(required = false, defaultValue = "") String barnId,
            @RequestParam(required = false, defaultValue = "") String reader,
            @RequestParam(required = false, defaultValue = "") String animal,
            @RequestParam(required = false, defaultValue = "") String direction,
            @RequestParam(required = false, defaultValue = "") String method,
            @RequestParam(required = false, defaultValue = "today") String preset,
            @RequestParam(required = false, defaultValue = "") String from,
            @RequestParam(required = false, defaultValue = "") String to,
            @RequestParam(required = false, defaultValue = "") String sort,
            @RequestParam(required = false, defaultValue = "") String dir,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "25") int size) {
        ReportWindow.Window window = window(preset, from, to);
        var scope = scope();
        var filter = new ReportQueries.PassageFilter(farmId, barnId, reader, animal, direction, method,
                window.fromInclusive(), window.toExclusive(), sort, dir);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodLabel", window.label());
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("sort", SortSpec.effectiveKey(sort, ReportQueries.PASSAGE_SORT, "time"));
        body.put("dir", SortSpec.effectiveDir(sort, dir, ReportQueries.PASSAGE_SORT, "time", "desc"));
        if (scope.seesNothing()) {
            body.put("items", List.of());
            body.put("total", 0);
            body.put("inCount", 0);
            body.put("outCount", 0);
            return ResponseEntity.ok(body);
        }
        var totalsQuery = ReportQueries.passageTotals(scope, filter);
        Map<String, Object> totals = jdbcTemplate.queryForMap(totalsQuery.sql(), totalsQuery.params().toArray());
        body.put("items", run(ReportQueries.passagePage(scope, filter, safeSize, (safePage - 1) * safeSize)));
        body.put("total", num(totals.get("total")));
        body.put("inCount", num(totals.get("in_count")));
        body.put("outCount", num(totals.get("out_count")));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/passages/export")
    @Audited(action = "EXPORT_PASSAGES_CSV", targetType = "REPORT")
    public ResponseEntity<?> exportPassages(
            @RequestParam(required = false, defaultValue = "") String farmId,
            @RequestParam(required = false, defaultValue = "") String barnId,
            @RequestParam(required = false, defaultValue = "") String reader,
            @RequestParam(required = false, defaultValue = "") String animal,
            @RequestParam(required = false, defaultValue = "") String direction,
            @RequestParam(required = false, defaultValue = "") String method,
            @RequestParam(required = false, defaultValue = "today") String preset,
            @RequestParam(required = false, defaultValue = "") String from,
            @RequestParam(required = false, defaultValue = "") String to) {
        ReportWindow.Window window = window(preset, from, to);
        var scope = scope();
        var filter = new ReportQueries.PassageFilter(farmId, barnId, reader, animal, direction, method,
                window.fromInclusive(), window.toExclusive(), "time", "desc");
        List<Map<String, Object>> rows = scope.seesNothing() ? List.of()
                : run(ReportQueries.passageExport(scope, filter, EXPORT_LIMIT));

        String[] columns = {"occurred_at", "animal_id", "direction", "detection_method", "crossing_status", "gate_id", "gate_label",
                "gate_reader", "read_reader", "card_uuid", "barn_code", "farm_name", "movement_id"};
        StringBuilder csv = new StringBuilder(String.join(",", columns)).append("\n");
        for (Map<String, Object> r : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) line.append(',');
                line.append(ReportQueries.csvCell(r.get(columns[i])));
            }
            csv.append(line).append("\n");
        }
        if (rows.size() >= EXPORT_LIMIT) {
            csv.append("\"NOTE: export stopped at ").append(EXPORT_LIMIT).append(" rows. Narrow the period or filters.\"\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vinabeef_gate_passages.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(("﻿" + csv).getBytes(StandardCharsets.UTF_8));
    }

    /** Every read a reader reported, with the result it led to: a movement, or nothing (repeat read, unknown tag, telemetry). */
    @GetMapping("/reads")
    public ResponseEntity<?> reads(
            @RequestParam(required = false, defaultValue = "") String farmId,
            @RequestParam(required = false, defaultValue = "") String barnId,
            @RequestParam(required = false, defaultValue = "") String reader,
            @RequestParam(required = false, defaultValue = "") String animal,
            @RequestParam(required = false, defaultValue = "") String event,
            @RequestParam(required = false, defaultValue = "") String result,
            @RequestParam(required = false, defaultValue = "today") String preset,
            @RequestParam(required = false, defaultValue = "") String from,
            @RequestParam(required = false, defaultValue = "") String to,
            @RequestParam(required = false, defaultValue = "") String sort,
            @RequestParam(required = false, defaultValue = "") String dir,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "25") int size) {
        ReportWindow.Window window = window(preset, from, to);
        var scope = scope();
        var filter = new ReportQueries.ReadFilter(farmId, barnId, reader, animal, event, result,
                window.fromInclusive(), window.toExclusive(), sort, dir);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodLabel", window.label());
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("sort", SortSpec.effectiveKey(sort, ReportQueries.READ_SORT, "time"));
        body.put("dir", SortSpec.effectiveDir(sort, dir, ReportQueries.READ_SORT, "time", "desc"));
        if (scope.seesNothing()) {
            body.put("items", List.of());
            body.put("total", 0);
            body.put("withMovement", 0);
            return ResponseEntity.ok(body);
        }
        var totalsQuery = ReportQueries.readTotals(scope, filter);
        Map<String, Object> totals = jdbcTemplate.queryForMap(totalsQuery.sql(), totalsQuery.params().toArray());
        body.put("items", run(ReportQueries.readPage(scope, filter, safeSize, (safePage - 1) * safeSize)));
        body.put("total", num(totals.get("total")));
        body.put("withMovement", num(totals.get("with_movement")));
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private ReportWindow.Window window(String preset, String from, String to) {
        try {
            return ReportWindow.resolve(preset, from, to, clock.zone(), clock.now());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private ReportQueries.Scope scope() {
        return ReportQueries.Scope.of(currentScope.getCurrentScope());
    }

    private List<Map<String, Object>> run(ReportQueries.Query q) {
        return jdbcTemplate.queryForList(q.sql(), q.params().toArray());
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
