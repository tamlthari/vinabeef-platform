package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthReportQueriesTest {

    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-12-01T00:00:00Z");
    private static final ReportQueries.Scope FARM = ReportQueries.Scope.farm("FARM-DAK-LAK");

    private static void assertBound(ReportQueries.Query q) {
        long placeholders = q.sql().chars().filter(c -> c == '?').count();
        assertEquals(placeholders, q.params().size(), "every ? needs exactly one parameter:\n" + q.sql());
    }

    private static HealthReportQueries.EventFilter filter(String farm, String barn, String animal, String text, String status,
                                                           String sort, String dir) {
        return new HealthReportQueries.EventFilter(farm, barn, animal, text, status, FROM, TO, sort, dir);
    }

    @Test
    void everyStatementIsFullyBound() {
        var f = filter("FARM-DAK-LAK", "BARN-DL-01", "cow", "100%", "QUARANTINE", "product", "asc");
        for (var kind : HealthReportQueries.EventKind.values()) {
            assertBound(HealthReportQueries.eventPage(FARM, kind, f, 25, 50));
            assertBound(HealthReportQueries.eventTotals(ReportQueries.Scope.ALL, kind, f));
        }
        var s = new HealthReportQueries.StatusFilter("FARM-DAK-LAK", "BARN-DL-01", "cow", "BOTH", "OUT_PASTURE", "barn", "desc");
        assertBound(HealthReportQueries.statusPage(ReportQueries.Scope.barns(List.of("B1", "B2")), s, 25, 0));
        assertBound(HealthReportQueries.statusTotals(FARM, s));
        assertBound(HealthReportQueries.dashboardNow(FARM));
        assertBound(HealthReportQueries.dashboardEvents(FARM, FROM));
    }

    @Test
    void theScopeIsAlwaysInTheStatementBeforeAnyFilter() {
        var q = HealthReportQueries.eventPage(FARM, HealthReportQueries.EventKind.ALL,
                filter("FARM-GIA-LAI", "BARN-GL-01", null, null, null, null, null), 25, 0);
        // window, then the person's own farm, then what they asked for: a filter can only narrow
        assertEquals(List.of(java.sql.Timestamp.from(FROM), java.sql.Timestamp.from(TO), "FARM-DAK-LAK", "FARM-GIA-LAI", "BARN-GL-01", 25, 0),
                q.params());
        assertTrue(HealthReportQueries.eventPage(ReportQueries.Scope.NONE, HealthReportQueries.EventKind.ALL,
                filter(null, null, null, null, null, null, null), 25, 0).sql().contains("1 = 0"));
        assertTrue(HealthReportQueries.statusPage(ReportQueries.Scope.NONE,
                new HealthReportQueries.StatusFilter(null, null, null, null, null, null, null), 25, 0).sql().contains("1 = 0"));
    }

    @Test
    void requestTextNeverReachesTheSql() {
        String evil = "x'; DROP TABLE animals;--";
        var q = HealthReportQueries.eventPage(ReportQueries.Scope.ALL, HealthReportQueries.EventKind.of(evil),
                filter(evil, evil, evil, evil, evil, evil, evil), 25, 0);
        assertFalse(q.sql().contains("DROP"));
        assertTrue(q.params().contains(evil));        // as a value, bound
        var s = HealthReportQueries.statusPage(ReportQueries.Scope.ALL,
                new HealthReportQueries.StatusFilter(evil, evil, evil, evil, evil, evil, evil), 25, 0);
        assertFalse(s.sql().contains("DROP"));
    }

    @Test
    void unknownSortKeysFallBackToNewestFirstAndOldestQuarantineFirst() {
        assertTrue(HealthReportQueries.eventPage(ReportQueries.Scope.ALL, HealthReportQueries.EventKind.ALL,
                filter(null, null, null, null, null, "nope", "sideways"), 25, 0).sql().contains("ORDER BY hr.event_at DESC NULLS LAST, hr.health_id ASC"));
        assertTrue(HealthReportQueries.statusPage(ReportQueries.Scope.ALL,
                new HealthReportQueries.StatusFilter(null, null, null, null, null, "nope", null), 25, 0).sql()
                .contains("ORDER BY ep.since ASC NULLS LAST, a.animal_id ASC"));
    }

    @Test
    void eventKindsAreDefinedByTheRecordTypeOrTheStatusItLeft() {
        assertEquals(HealthReportQueries.EventKind.ALL, HealthReportQueries.EventKind.of(null));
        assertEquals(HealthReportQueries.EventKind.QUARANTINE, HealthReportQueries.EventKind.of("quarantine"));
        assertTrue(HealthReportQueries.EventKind.QUARANTINE.condition.contains("resulting_status' = 'QUARANTINE'"));
        assertTrue(HealthReportQueries.EventKind.TREATMENT.condition.contains("record_type = 'TREATMENT'"));
        assertTrue(HealthReportQueries.EventKind.TREATMENT.condition.contains("resulting_status' = 'UNDER_TREATMENT'"));
        assertTrue(HealthReportQueries.EventKind.VACCINATION.condition.contains("record_type = 'VACCINATION'"));
    }

    @Test
    void theStatusReportDefaultsToQuarantineAndNeverListsArchivedAnimals() {
        var q = HealthReportQueries.statusPage(ReportQueries.Scope.ALL,
                new HealthReportQueries.StatusFilter(null, null, null, null, null, null, null), 25, 0);
        assertTrue(q.sql().contains("a.health_status = 'QUARANTINE'"));
        assertTrue(q.sql().contains("a.lifecycle_status <> 'ARCHIVED'"));
        assertTrue(HealthReportQueries.statusPage(ReportQueries.Scope.ALL,
                new HealthReportQueries.StatusFilter(null, null, null, "both", null, null, null), 25, 0).sql()
                .contains("IN ('QUARANTINE', 'UNDER_TREATMENT')"));
    }

    @Test
    void csvCellsAreQuotedSafeAndTimesAreIso() {
        String csv = ReportQueries.csv(new String[]{"a", "b"},
                List.of(java.util.Map.of("a", "=SUM(1)", "b", java.sql.Timestamp.from(Instant.parse("2026-11-20T01:02:03Z")))));
        assertEquals("a,b\n\"'=SUM(1)\",\"2026-11-20T01:02:03Z\"\n", csv);
    }
}
