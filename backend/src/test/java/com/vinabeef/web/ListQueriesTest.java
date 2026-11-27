package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListQueriesTest {

    private static final Instant DAY_START = Instant.parse("2026-11-20T17:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-11-21T15:23:01Z");

    private static void assertBound(ReportQueries.Query q) {
        int placeholders = 0;
        for (char c : q.sql().toCharArray()) {
            if (c == '?') placeholders++;
        }
        assertEquals(placeholders, q.params().size(), "every ? needs exactly one parameter:\n" + q.sql());
    }

    @Test
    void cattleStatementsAreFullyBound() {
        var farm = ReportQueries.Scope.farm("FARM-DONG-NAI");
        assertBound(ListQueries.cattleCount(farm, "vb-1"));
        assertBound(ListQueries.cattlePage(farm, "vb-1", "barn", "desc", 100, 200));
        assertBound(ListQueries.cattlePage(ReportQueries.Scope.ALL, "", null, null, 100, 0));
        assertBound(ListQueries.cattlePage(ReportQueries.Scope.barns(List.of("B1", "B2")), "", "weight", "asc", 100, 0));
    }

    @Test
    void cattleDefaultIsNewestFirstWithAStableTieBreak() {
        var q = ListQueries.cattlePage(ReportQueries.Scope.ALL, "", null, null, 100, 0);

        assertTrue(q.sql().contains("ROW_NUMBER() OVER (ORDER BY a.created_at DESC NULLS LAST, a.animal_id ASC)"));
        assertTrue(q.sql().contains("ORDER BY a.rn"));
    }

    @Test
    void cattleSortKeysAreFixedSqlAndNeverRequestText() {
        var q = ListQueries.cattlePage(ReportQueries.Scope.ALL, "", "id'; DROP TABLE animals;--", "desc", 100, 0);

        assertFalse(q.sql().contains("DROP"));
        assertTrue(ListQueries.cattlePage(ReportQueries.Scope.ALL, "", "presence", "asc", 100, 0).sql()
                .contains("COALESCE(ps.presence_status, 'OUT_PASTURE') ASC NULLS LAST"));
    }

    @Test
    void archivedCattleAreNeverListed() {
        assertTrue(ListQueries.cattleCount(ReportQueries.Scope.ALL, "").sql().contains("a.lifecycle_status <> 'ARCHIVED'"));
    }

    @Test
    void searchWildcardsAreEscaped() {
        var q = ListQueries.cattleCount(ReportQueries.Scope.ALL, "100%_x");

        assertEquals(6, q.params().size());
        assertTrue(q.params().stream().allMatch(p -> "%100\\%\\_x%".equals(p)));
    }

    @Test
    void readerStatementIsBoundInTheOrderTheMarksAppear() {
        var q = ListQueries.readers(ReportQueries.Scope.farm("F"), "c0f8", "in", "desc", DAY_START, DAY_END);

        assertBound(q);
        // four "today" bounds first, then the farm, then five search patterns
        assertEquals(4 + 1 + 5, q.params().size());
        assertEquals(java.sql.Timestamp.from(DAY_START), q.params().get(0));
        assertEquals(java.sql.Timestamp.from(DAY_END), q.params().get(1));
        assertEquals("F", q.params().get(4));
    }

    @Test
    void readerDefaultIsNewestFirstAndRetiredReadersAreHidden() {
        var q = ListQueries.readers(ReportQueries.Scope.ALL, "", null, null, DAY_START, DAY_END);

        assertTrue(q.sql().contains("g.retired_at IS NULL"));
        assertTrue(q.sql().endsWith("ORDER BY g.created_at DESC NULLS LAST, g.gate_id ASC"));
    }

    @Test
    void readerCanBeSortedByTheSeenTimeAndByInOutCounts() {
        assertTrue(ListQueries.readers(ReportQueries.Scope.ALL, "", "last_seen", "desc", DAY_START, DAY_END).sql()
                .endsWith("ORDER BY last_seen DESC NULLS LAST, g.gate_id ASC"));
        assertTrue(ListQueries.readers(ReportQueries.Scope.ALL, "", "out", "asc", DAY_START, DAY_END).sql()
                .endsWith("ORDER BY mc.out_count ASC NULLS LAST, g.gate_id ASC"));
    }

    @Test
    void recentMovementsCarryReaderNamesAndApplyTheScopeBeforeTheLimit() {
        var all = ListQueries.recentMovements(ReportQueries.Scope.ALL, 15);
        assertBound(all);
        assertTrue(all.sql().contains("sr.reader_name AS read_reader"), "the reader that completed the pass");
        assertTrue(all.sql().contains("fr.reader_name AS first_reader"), "the reader that saw the animal first");
        assertTrue(all.sql().contains("g.reader_name AS gate_reader") && all.sql().contains("g.label       AS gate_label"));
        assertTrue(all.sql().contains("mv.crossing_status"), "so the stream can mark a half crossing");
        assertTrue(all.sql().contains("ORDER BY mv.occurred_at DESC, mv.movement_id DESC LIMIT ?"));
        assertEquals(List.of(15), all.params());

        var farm = ListQueries.recentMovements(ReportQueries.Scope.farm("FARM-DONG-NAI"), 15);
        assertBound(farm);
        assertTrue(farm.sql().indexOf("b.farm_id = ?") > 0 && farm.sql().indexOf("b.farm_id = ?") < farm.sql().indexOf("LIMIT"),
                "scope must be inside the statement, before LIMIT");
        assertEquals(List.of("FARM-DONG-NAI", 15), farm.params());

        var barns = ListQueries.recentMovements(ReportQueries.Scope.barns(List.of("B1", "B2")), 15);
        assertBound(barns);
        assertEquals(List.of("B1", "B2", 15), barns.params());

        assertTrue(ListQueries.recentMovements(ReportQueries.Scope.NONE, 15).sql().contains("1 = 0"));
    }

    // ------------------------------------------------------------------------------------------------ column filters

    @Test
    void cattleFiltersAreBoundAndApplyTogether() {
        var f = ListQueries.CattleFilter.parse("FARM-DAK-LAK", "BARN-DL-01,BARN-DL-02", "UNDER_TREATMENT,QUARANTINE",
                "OUT_PASTURE", "with", "none", "seen");
        var q = ListQueries.cattlePage(ReportQueries.Scope.farm("FARM-DAK-LAK"), "vb", f, "barn", "asc", 100, 0);
        assertBound(q);
        assertBound(ListQueries.cattleCount(ReportQueries.Scope.ALL, "", f));
        for (String part : new String[]{"b.farm_id = ?", "a.assigned_barn_id IN (?, ?)", "health_status, 'HEALTHY') IN (?, ?)",
                "presence_status, 'OUT_PASTURE') IN (?)", "NOT EXISTS (SELECT 1 FROM health_records", "EXISTS (SELECT 1 FROM movements"}) {
            assertTrue(q.sql().contains(part), part);
        }
        // the person's own farm comes first in the parameters: the filter can only narrow
        assertEquals("FARM-DAK-LAK", q.params().get(0));
        assertEquals("FARM-DAK-LAK", q.params().get(1));
    }

    @Test
    void noFilterLeavesTheOldStatementUnchanged() {
        assertEquals(ListQueries.cattlePage(ReportQueries.Scope.ALL, "x", "id", "asc", 100, 0).sql(),
                ListQueries.cattlePage(ReportQueries.Scope.ALL, "x", ListQueries.CattleFilter.NONE, "id", "asc", 100, 0).sql());
        assertTrue(ListQueries.CattleFilter.parse("ALL", " , ", "nope", "nope", "nope", "nope", "nope").isEmpty());
    }

    @Test
    void readerFiltersAreBoundAndUseTheSuppliedClock() {
        var now = Instant.parse("2026-11-21T10:00:00Z");
        var f = ListQueries.ReaderFilter.parse("FARM-DAK-LAK", "BARN-DL-01", "in_only", "paired", "silent", "never_used");
        var q = ListQueries.readers(ReportQueries.Scope.ALL, "dr", f, "last_seen", "desc", DAY_START, DAY_END, now);
        assertBound(q);
        assertTrue(q.params().contains(java.sql.Timestamp.from(now.minusSeconds(86_400))));
        assertTrue(q.sql().contains("g.direction_mode = ?"));
        assertTrue(q.sql().contains("ls.last_seen IS NULL OR ls.last_seen < ?"));
        assertTrue(q.sql().endsWith("ORDER BY last_seen DESC NULLS LAST, g.gate_id ASC"));
        assertTrue(ListQueries.ReaderFilter.parse(null, "", "x", "x", "x", "x").isEmpty());
    }

    @Test
    void aReadersPageIsTheOrderedListWithALimitAndTheCountIsTheSameListWithoutOne() {
        var now = Instant.parse("2026-11-21T10:00:00Z");
        var f = ListQueries.ReaderFilter.parse("FARM-DAK-LAK", "", "", "paired", "silent", "");
        var ordered = ListQueries.readers(ReportQueries.Scope.ALL, "dr", f, "barn", "asc", DAY_START, DAY_END, now);
        var page = ListQueries.readersPage(ReportQueries.Scope.ALL, "dr", f, "barn", "asc", DAY_START, DAY_END, now, 25, 50);
        var count = ListQueries.readerCount(ReportQueries.Scope.ALL, "dr", f, DAY_START, DAY_END, now);

        assertBound(page);
        assertBound(count);
        assertEquals(ordered.sql() + " LIMIT ? OFFSET ?", page.sql());
        assertEquals(ordered.params(), page.params().subList(0, ordered.params().size()), "the page filters exactly as the list does");
        assertEquals(List.of(25, 50), page.params().subList(ordered.params().size(), page.params().size()));
        assertTrue(count.sql().startsWith("SELECT COUNT(*) FROM ("));
        assertFalse(count.sql().contains("ORDER BY"), "counting needs no order");
        assertFalse(count.sql().contains("LIMIT"), "the total is not limited to a page");
        assertEquals(ordered.params(), count.params(), "the total counts exactly what the pages hold");
    }

    @Test
    void aNegativeOffsetOrAZeroLimitCannotReachTheStatement() {
        var page = ListQueries.readersPage(ReportQueries.Scope.ALL, "", ListQueries.ReaderFilter.NONE, null, null,
                DAY_START, DAY_END, DAY_END, 0, -25);
        assertEquals(List.of(1, 0), page.params().subList(page.params().size() - 2, page.params().size()));
    }
}
