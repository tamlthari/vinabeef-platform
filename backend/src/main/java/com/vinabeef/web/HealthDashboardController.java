package com.vinabeef.web;

import com.vinabeef.security.CurrentScopeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the dashboard shows about animal health: how many animals are in quarantine or under treatment now, the newest
 * vaccination / treatment / quarantine events, and the animals in quarantine. Every person who can open the dashboard may
 * call these; each sees only their own farm or barns (the same scope as everywhere else).
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class HealthDashboardController {

    /** The "recent" in the counters of the dashboard tiles. */
    static final int RECENT_DAYS = 7;
    static final int MAX_ROWS = 50;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentScopeProvider currentScope;
    private final ReportClock clock;

    /** Tile numbers: animals in quarantine / under treatment now, and the events recorded in the last {@value #RECENT_DAYS} days. */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        var scope = ReportQueries.Scope.of(currentScope.getCurrentScope());
        Map<String, Object> now = new LinkedHashMap<>();
        Map<String, Object> recent = new LinkedHashMap<>();
        if (scope.seesNothing()) {
            now.put("quarantineNow", 0);
            now.put("treatmentNow", 0);
            now.put("quarantineAtPasture", 0);
            recent.put("events", 0);
            recent.put("vaccinations", 0);
            recent.put("treatments", 0);
            recent.put("quarantineEvents", 0);
        } else {
            var n = one(HealthReportQueries.dashboardNow(scope));
            now.put("quarantineNow", num(n.get("quarantine_now")));
            now.put("treatmentNow", num(n.get("treatment_now")));
            now.put("quarantineAtPasture", num(n.get("quarantine_at_pasture")));
            var e = one(HealthReportQueries.dashboardEvents(scope, clock.now().minus(Duration.ofDays(RECENT_DAYS))));
            recent.put("events", num(e.get("all_events")));
            recent.put("vaccinations", num(e.get("vaccinations")));
            recent.put("treatments", num(e.get("treatments")));
            recent.put("quarantineEvents", num(e.get("quarantine_events")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("now", now);
        out.put("recent", recent);
        out.put("recentDays", RECENT_DAYS);
        return ResponseEntity.ok(out);
    }

    /** The newest events of one kind (ALL, VACCINATION, TREATMENT, QUARANTINE, HEALTH_CHECK), newest first. */
    @GetMapping("/events")
    public ResponseEntity<List<Map<String, Object>>> events(
            @RequestParam(required = false, defaultValue = "ALL") String kind,
            @RequestParam(required = false, defaultValue = "12") int limit) {
        var scope = ReportQueries.Scope.of(currentScope.getCurrentScope());
        if (scope.seesNothing()) {
            return ResponseEntity.ok(List.of());
        }
        // all time, and a day ahead: an event written by a phone whose clock runs fast must not be hidden from "recent"
        var filter = new HealthReportQueries.EventFilter(null, null, null, null, null,
                Instant.EPOCH, clock.now().plus(Duration.ofDays(1)), "", "");
        var q = HealthReportQueries.eventPage(scope, HealthReportQueries.EventKind.of(kind), filter, clamp(limit), 0);
        return ResponseEntity.ok(jdbcTemplate.queryForList(q.sql(), q.params().toArray()));
    }

    /** The animals in quarantine (or, with {@code status=BOTH} / {@code UNDER_TREATMENT}, under treatment), longest first. */
    @GetMapping("/quarantine")
    public ResponseEntity<List<Map<String, Object>>> quarantine(
            @RequestParam(required = false, defaultValue = "QUARANTINE") String status,
            @RequestParam(required = false, defaultValue = "12") int limit) {
        var scope = ReportQueries.Scope.of(currentScope.getCurrentScope());
        if (scope.seesNothing()) {
            return ResponseEntity.ok(List.of());
        }
        var filter = new HealthReportQueries.StatusFilter(null, null, null, status, null, "", "");
        var q = HealthReportQueries.statusPage(scope, filter, clamp(limit), 0);
        return ResponseEntity.ok(jdbcTemplate.queryForList(q.sql(), q.params().toArray()));
    }

    private Map<String, Object> one(ReportQueries.Query q) {
        return jdbcTemplate.queryForMap(q.sql(), q.params().toArray());
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), MAX_ROWS);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
