package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportQueriesTest {

    private static final Instant FROM = Instant.parse("2026-11-20T17:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-21T17:00:00Z");

    private static int placeholders(String sql) {
        int n = 0;
        for (char c : sql.toCharArray()) {
            if (c == '?') n++;
        }
        return n;
    }

    private static void assertBound(ReportQueries.Query q) {
        assertEquals(placeholders(q.sql()), q.params().size(), "every ? needs exactly one parameter:\n" + q.sql());
    }

    private static ReportQueries.PassageFilter passages(String farm, String barn, String reader, String animal,
                                                        String direction, String method, String sort, String dir) {
        return new ReportQueries.PassageFilter(farm, barn, reader, animal, direction, method, FROM, TO, sort, dir);
    }

    @Test
    void everyStatementHasExactlyAsManyParametersAsPlaceholders() {
        var all = ReportQueries.Scope.ALL;
        var barns = ReportQueries.Scope.barns(List.of("B1", "B2", "B3"));

        assertBound(ReportQueries.herdBalance(all, "FARM-1", FROM, TO));
        assertBound(ReportQueries.herdBalance(barns, null, FROM, TO));
        assertBound(ReportQueries.passagePage(ReportQueries.Scope.farm("F"), passages("ALL", "B1", "c0f8", "vb-1", "in", "paired_readers", "time", "desc"), 25, 50));
        assertBound(ReportQueries.passageTotals(barns, passages("", "", "", "", "", "", null, null)));
        assertBound(ReportQueries.readPage(all, new ReportQueries.ReadFilter("", "", "c1c4", "cow", "lidar", "none", FROM, TO, "reader", "asc"), 25, 0));
        assertBound(ReportQueries.readTotals(ReportQueries.Scope.farm("F"), new ReportQueries.ReadFilter("", "", "", "", "", "movement", FROM, TO, null, null)));
    }

    @Test
    void theHalfCrossingFilterIsAStatusNotADetectionMethod() {
        var half = ReportQueries.passagePage(ReportQueries.Scope.ALL, passages("", "", "", "", "", "half_crossing", null, null), 10, 0);
        assertBound(half);
        assertTrue(half.sql().contains("mv.crossing_status = 'HALF_CROSSING'"));
        assertFalse(half.sql().contains("mv.detection_method = ?"));

        var paired = ReportQueries.passagePage(ReportQueries.Scope.ALL, passages("", "", "", "", "", "paired_readers", null, null), 10, 0);
        assertBound(paired);
        assertTrue(paired.sql().contains("mv.detection_method = ?"), "paired readers still means every passage found by two readers");
        assertTrue(paired.params().contains("PAIRED_READERS"));
    }

    @Test
    void thePassageReportCarriesTheCrossingStatus() {
        assertTrue(ReportQueries.passagePage(ReportQueries.Scope.ALL, passages("", "", "", "", "", "", null, null), 10, 0)
                .sql().contains("mv.crossing_status"));
    }

    @Test
    void textFromTheRequestIsNeverPartOfTheSql() {
        String evil = "x'; DROP TABLE movements; --";
        var q = ReportQueries.passagePage(ReportQueries.Scope.ALL,
                passages(evil, evil, evil, evil, evil, evil, evil, evil), 25, 0);

        assertFalse(q.sql().contains("DROP"));
        assertFalse(q.sql().contains("x'"));
        assertTrue(q.sql().contains("ORDER BY mv.occurred_at DESC NULLS LAST, mv.movement_id ASC"));
    }

    @Test
    void sortKeysMapToFixedColumnsAndAnUnknownKeyFallsBackToNewestFirst() {
        assertTrue(ReportQueries.passagePage(ReportQueries.Scope.ALL,
                passages("", "", "", "", "", "", "reader", "asc"), 10, 0).sql().contains("ORDER BY g.reader_name ASC NULLS LAST, mv.movement_id ASC"));
        assertTrue(ReportQueries.passagePage(ReportQueries.Scope.ALL,
                passages("", "", "", "", "", "", "nonsense", null), 10, 0).sql().contains("ORDER BY mv.occurred_at DESC"));
    }

    @Test
    void aPersonWithNoScopeCanNeverMatchARow() {
        var q = ReportQueries.passageTotals(ReportQueries.Scope.NONE, passages("", "", "", "", "", "", null, null));

        assertTrue(q.sql().contains("AND 1 = 0"));
        assertTrue(ReportQueries.Scope.NONE.seesNothing());
        assertTrue(ReportQueries.Scope.barns(List.of()).seesNothing());
        assertTrue(ReportQueries.Scope.farm(null).seesNothing());
    }

    @Test
    void scopeRestrictsByFarmOrByTheListedBarns() {
        var farm = ReportQueries.passageTotals(ReportQueries.Scope.farm("FARM-DONG-NAI"), passages("", "", "", "", "", "", null, null));
        var barns = ReportQueries.passageTotals(ReportQueries.Scope.barns(List.of("B1", "B2")), passages("", "", "", "", "", "", null, null));

        assertTrue(farm.sql().contains("b.farm_id = ?"));
        assertTrue(farm.params().contains("FARM-DONG-NAI"));
        assertTrue(barns.sql().contains("b.barn_id IN (?,?)"));
        assertTrue(barns.params().containsAll(List.of("B1", "B2")));
    }

    @Test
    void searchTextHasItsLikeWildcardsEscaped() {
        var q = ReportQueries.passageTotals(ReportQueries.Scope.ALL, passages("", "", "", "50%_off", "", "", null, null));

        assertTrue(q.params().contains("%50\\%\\_off%"));
    }

    @Test
    void aBadDirectionIsIgnoredInsteadOfBeingFiltered() {
        var q = ReportQueries.passageTotals(ReportQueries.Scope.ALL, passages("", "", "", "", "sideways", "", null, null));

        assertFalse(q.sql().contains("mv.direction = ?"));
        assertEquals(2, q.params().size());
    }

    @Test
    void csvCellsAreQuotedAndFormulaStartsAreDefused() {
        assertEquals("\"VB-000001\"", ReportQueries.csvCell("VB-000001"));
        assertEquals("\"say \"\"hi\"\"\"", ReportQueries.csvCell("say \"hi\""));
        assertEquals("\"'=HYPERLINK(1)\"", ReportQueries.csvCell("=HYPERLINK(1)"));
        assertEquals("\"'@SUM(A1)\"", ReportQueries.csvCell("@SUM(A1)"));
        assertEquals("", ReportQueries.csvCell(null));
    }
}
