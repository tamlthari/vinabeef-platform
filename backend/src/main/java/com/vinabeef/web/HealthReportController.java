package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.security.CurrentScopeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reporting page, health reports: vaccinations, treatments, health history (every record, one type or all) and the current
 * quarantine status. Same scope, time window and paging rules as the gate reports in {@link ReportController}.
 */
@RestController
@RequestMapping("/api/v1/reports/health")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'EXECUTIVE', 'FARM_MANAGER')")
public class HealthReportController {

    private static final int EXPORT_LIMIT = 50_000;

    /** The four reports, by the word that ends their URL. */
    enum Report {
        VACCINATIONS("vaccinations", HealthReportQueries.EventKind.VACCINATION),
        TREATMENTS("treatments", HealthReportQueries.EventKind.TREATMENT),
        HISTORY("history", null),                       // the kind comes from the "recordType" filter
        QUARANTINE("quarantine", null);                 // not events: the animals in that status now

        final String slug;
        final HealthReportQueries.EventKind fixedKind;

        Report(String slug, HealthReportQueries.EventKind fixedKind) {
            this.slug = slug;
            this.fixedKind = fixedKind;
        }

        boolean isStatusReport() {
            return this == QUARANTINE;
        }

        static Optional<Report> of(String slug) {
            for (Report r : values()) {
                if (r.slug.equalsIgnoreCase(slug == null ? "" : slug.trim())) {
                    return Optional.of(r);
                }
            }
            return Optional.empty();
        }
    }

    static final String[] EVENT_CSV = {"event_at", "animal_id", "record_type", "title", "product", "batch", "dose",
            "resulting_status", "next_due_date", "administered_by", "barn_code", "farm_name", "notes", "health_id"};
    static final String[] STATUS_CSV = {"animal_id", "health_status", "since", "days", "presence_status", "title", "product",
            "notes", "quarantine_notes", "last_event_at", "administered_by", "barn_code", "farm_name"};

    private final JdbcTemplate jdbcTemplate;
    private final CurrentScopeProvider currentScope;
    private final ReportClock clock;

    @GetMapping("/{kind}")
    public ResponseEntity<?> report(
            @PathVariable String kind,
            @RequestParam(required = false, defaultValue = "") String farmId,
            @RequestParam(required = false, defaultValue = "") String barnId,
            @RequestParam(required = false, defaultValue = "") String animal,
            @RequestParam(required = false, defaultValue = "") String text,
            @RequestParam(required = false, defaultValue = "") String status,
            @RequestParam(required = false, defaultValue = "") String recordType,
            @RequestParam(required = false, defaultValue = "") String presence,
            @RequestParam(required = false, defaultValue = "30d") String preset,
            @RequestParam(required = false, defaultValue = "") String from,
            @RequestParam(required = false, defaultValue = "") String to,
            @RequestParam(required = false, defaultValue = "") String sort,
            @RequestParam(required = false, defaultValue = "") String dir,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "25") int size) {
        Report report = Report.of(kind).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown health report '" + kind + "'."));
        var scope = ReportQueries.Scope.of(currentScope.getCurrentScope());
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("page", safePage);
        body.put("size", safeSize);

        if (report.isStatusReport()) {
            var filter = new HealthReportQueries.StatusFilter(farmId, barnId, animal, status, presence, sort, dir);
            body.put("periodLabel", "Current status (no period)");
            body.put("sort", SortSpec.effectiveKey(sort, HealthReportQueries.STATUS_SORT, "since"));
            body.put("dir", SortSpec.effectiveDir(sort, dir, HealthReportQueries.STATUS_SORT, "since", "asc"));
            if (scope.seesNothing()) {
                return ResponseEntity.ok(empty(body));
            }
            var totalsQuery = HealthReportQueries.statusTotals(scope, filter);
            Map<String, Object> totals = jdbcTemplate.queryForMap(totalsQuery.sql(), totalsQuery.params().toArray());
            body.put("items", run(HealthReportQueries.statusPage(scope, filter, safeSize, (safePage - 1) * safeSize)));
            body.put("total", num(totals.get("total")));
            body.put("quarantineCount", num(totals.get("quarantine_count")));
            body.put("treatmentCount", num(totals.get("treatment_count")));
            body.put("atPastureCount", num(totals.get("at_pasture_count")));
            return ResponseEntity.ok(body);
        }

        ReportWindow.Window window = window(preset, from, to);
        var kindOfEvent = report.fixedKind != null ? report.fixedKind : HealthReportQueries.EventKind.of(recordType);
        var filter = new HealthReportQueries.EventFilter(farmId, barnId, animal, text, status,
                window.fromInclusive(), window.toExclusive(), sort, dir);
        body.put("periodLabel", window.label());
        body.put("sort", SortSpec.effectiveKey(sort, HealthReportQueries.EVENT_SORT, "time"));
        body.put("dir", SortSpec.effectiveDir(sort, dir, HealthReportQueries.EVENT_SORT, "time", "desc"));
        if (scope.seesNothing()) {
            return ResponseEntity.ok(empty(body));
        }
        var totalsQuery = HealthReportQueries.eventTotals(scope, kindOfEvent, filter);
        Map<String, Object> totals = jdbcTemplate.queryForMap(totalsQuery.sql(), totalsQuery.params().toArray());
        body.put("items", run(HealthReportQueries.eventPage(scope, kindOfEvent, filter, safeSize, (safePage - 1) * safeSize)));
        body.put("total", num(totals.get("total")));
        body.put("animals", num(totals.get("animals")));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{kind}/export")
    @Audited(action = "EXPORT_HEALTH_REPORT_CSV", targetType = "REPORT")
    public ResponseEntity<?> export(
            @PathVariable String kind,
            @RequestParam(required = false, defaultValue = "") String farmId,
            @RequestParam(required = false, defaultValue = "") String barnId,
            @RequestParam(required = false, defaultValue = "") String animal,
            @RequestParam(required = false, defaultValue = "") String text,
            @RequestParam(required = false, defaultValue = "") String status,
            @RequestParam(required = false, defaultValue = "") String recordType,
            @RequestParam(required = false, defaultValue = "") String presence,
            @RequestParam(required = false, defaultValue = "30d") String preset,
            @RequestParam(required = false, defaultValue = "") String from,
            @RequestParam(required = false, defaultValue = "") String to) {
        Report report = Report.of(kind).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown health report '" + kind + "'."));
        var scope = ReportQueries.Scope.of(currentScope.getCurrentScope());

        List<Map<String, Object>> rows;
        String[] columns;
        if (report.isStatusReport()) {
            columns = STATUS_CSV;
            rows = scope.seesNothing() ? List.of() : run(HealthReportQueries.statusPage(scope,
                    new HealthReportQueries.StatusFilter(farmId, barnId, animal, status, presence, "", ""), EXPORT_LIMIT, 0));
        } else {
            columns = EVENT_CSV;
            ReportWindow.Window window = window(preset, from, to);
            var kindOfEvent = report.fixedKind != null ? report.fixedKind : HealthReportQueries.EventKind.of(recordType);
            rows = scope.seesNothing() ? List.of() : run(HealthReportQueries.eventPage(scope, kindOfEvent,
                    new HealthReportQueries.EventFilter(farmId, barnId, animal, text, status,
                            window.fromInclusive(), window.toExclusive(), "", ""), EXPORT_LIMIT, 0));
        }
        StringBuilder csv = new StringBuilder(ReportQueries.csv(columns, rows));
        if (rows.size() >= EXPORT_LIMIT) {
            csv.append("\"NOTE: export stopped at ").append(EXPORT_LIMIT).append(" rows. Narrow the period or filters.\"\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vinabeef_health_" + report.slug + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private Map<String, Object> empty(Map<String, Object> body) {
        body.put("items", List.of());
        body.put("total", 0);
        body.put("animals", 0);
        body.put("quarantineCount", 0);
        body.put("treatmentCount", 0);
        body.put("atPastureCount", 0);
        return body;
    }

    private ReportWindow.Window window(String preset, String from, String to) {
        try {
            return ReportWindow.resolve(preset, from, to, clock.zone(), clock.now());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private List<Map<String, Object>> run(ReportQueries.Query q) {
        return jdbcTemplate.queryForList(q.sql(), q.params().toArray());
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
