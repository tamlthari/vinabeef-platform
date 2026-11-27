package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertQueriesTest {

    private static void assertBound(ReportQueries.Query q) {
        int placeholders = 0;
        for (char c : q.sql().toCharArray()) {
            if (c == '?') placeholders++;
        }
        assertEquals(placeholders, q.params().size(), "every ? needs exactly one parameter:\n" + q.sql());
    }

    @Test
    void thePageSizeIsAtLeastOneAndAtMostOneHundred() {
        assertEquals(25, AlertQueries.pageSize(25));
        assertEquals(1, AlertQueries.pageSize(0));
        assertEquals(1, AlertQueries.pageSize(-5));
        assertEquals(100, AlertQueries.pageSize(100));
        assertEquals(100, AlertQueries.pageSize(5000));
    }

    @Test
    void aPageAndItsCountShareOneWhere() {
        var scope = ReportQueries.Scope.farm("FARM-DAK-LAK");
        var count = AlertQueries.count(scope, "open", "missing_cattle", "vb-0007");
        var page = AlertQueries.page(scope, "open", "missing_cattle", "vb-0007", 25, 50);

        assertBound(count);
        assertBound(page);
        // the same filters in the same order, so "page 3 of 3" is always true
        assertEquals(count.params(), page.params().subList(0, count.params().size()));
        assertEquals(List.of(25, 50), page.params().subList(count.params().size(), page.params().size()));
        assertEquals("FARM-DAK-LAK", count.params().get(0), "the person's own farm comes first: a filter can only narrow");
        assertEquals("OPEN", count.params().get(1));
        assertEquals("MISSING_CATTLE", count.params().get(2));
        assertFalse(count.sql().contains("LIMIT"));
        assertTrue(count.sql().startsWith("SELECT COUNT(*) "));
    }

    @Test
    void allStatesAndTypesAddNoCondition() {
        for (String blank : new String[]{"ALL", "all", "", "  ", null}) {
            var q = AlertQueries.count(ReportQueries.Scope.ALL, blank, blank, "");
            assertFalse(q.sql().contains("al.state = ?"), String.valueOf(blank));
            assertFalse(q.sql().contains("al.type = ?"), String.valueOf(blank));
            assertEquals(0, q.params().size());
        }
    }

    @Test
    void theNewestAlertComesFirstAndTiesHaveAFixedOrderSoNoRowMovesBetweenPages() {
        var q = AlertQueries.page(ReportQueries.Scope.ALL, "ALL", "ALL", "", 25, 0);
        assertTrue(q.sql().contains("ORDER BY al.raised_at DESC, al.alert_id DESC LIMIT ? OFFSET ?"));
    }

    @Test
    void scopeIsAppliedInsideTheStatement() {
        assertTrue(AlertQueries.page(ReportQueries.Scope.barns(List.of("B1", "B2")), "ALL", "ALL", "", 25, 0).sql()
                .contains("b.barn_id IN (?,?)"));
        assertTrue(AlertQueries.count(ReportQueries.Scope.NONE, "ALL", "ALL", "").sql().contains("1 = 0"));
        assertFalse(AlertQueries.count(ReportQueries.Scope.ALL, "ALL", "ALL", "").sql().contains("farm_id = ?"));
    }

    @Test
    void searchTextIsAPatternNotSqlAndWildcardsAreLiteral() {
        var q = AlertQueries.count(ReportQueries.Scope.ALL, "ALL", "ALL", "  50%_Off'; DROP TABLE alerts;-- ");
        assertBound(q);
        assertEquals(4, q.params().size());
        assertEquals("%50\\%\\_off'; drop table alerts;--%", q.params().get(0));
        assertFalse(q.sql().contains("DROP"), "text from the request is never put into the statement");
    }

    @Test
    void aNegativeOffsetOrAZeroLimitCannotReachTheStatement() {
        var q = AlertQueries.page(ReportQueries.Scope.ALL, "ALL", "ALL", "", 0, -25);
        assertEquals(List.of(1, 0), q.params());
    }
}
