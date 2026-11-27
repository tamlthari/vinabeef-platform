package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every kind of report scope must satisfy, over {@code values()}: whichever kind a person has, the statement it
 * adds is an AND condition on the barns alias with exactly as many parameters as placeholders.
 */
class ReportScopeKindTest {

    private static ReportQueries.Scope sample(ReportQueries.Scope.Kind kind) {
        return new ReportQueries.Scope(kind, "FARM-1", List.of("B1", "B2", "B3"));
    }

    @Test
    void everyKindAddsAnAndConditionWithMatchingParameters() {
        for (ReportQueries.Scope.Kind kind : ReportQueries.Scope.Kind.values()) {
            StringBuilder sql = new StringBuilder("SELECT 1 WHERE 1 = 1");
            List<Object> params = new ArrayList<>();
            ReportQueries.scope(sql, params, sample(kind), "b");
            String added = sql.substring("SELECT 1 WHERE 1 = 1".length());
            assertTrue(added.isEmpty() || added.startsWith(" AND "), kind + " must only append an AND condition: '" + added + "'");
            int placeholders = (int) added.chars().filter(c -> c == '?').count();
            assertEquals(placeholders, params.size(), kind + " parameter count");
        }
    }

    @Test
    void everyKindThatRestrictsNamesTheBarnsAlias() {
        for (ReportQueries.Scope.Kind kind : ReportQueries.Scope.Kind.values()) {
            StringBuilder sql = new StringBuilder();
            ReportQueries.scope(sql, new ArrayList<>(), sample(kind), "bx");
            if (sql.length() > 0 && !sql.toString().contains("1 = 0")) {
                assertTrue(sql.toString().contains("bx."), kind + " ignored the alias: " + sql);
            }
        }
    }

    @Test
    void aPersonWithNoScopeGetsAConditionThatIsNeverTrue() {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        ReportQueries.scope(sql, params, ReportQueries.Scope.NONE, "b");
        assertTrue(sql.toString().contains("1 = 0"));
        assertTrue(params.isEmpty());
    }

    @Test
    void anAllScopeAddsNothing() {
        StringBuilder sql = new StringBuilder();
        ReportQueries.scope(sql, new ArrayList<>(), ReportQueries.Scope.ALL, "b");
        assertEquals("", sql.toString());
    }

    @Test
    void aPersonWithoutAScopeSeesNothingNeverEverything() {
        var none = java.util.List.<String>of();
        assertEquals(ReportQueries.Scope.ALL, ReportQueries.Scope.of(new com.vinabeef.security.UserScope("a", "SYSTEM_ADMIN", null, none)));
        assertEquals(ReportQueries.Scope.ALL, ReportQueries.Scope.of(new com.vinabeef.security.UserScope("e", "EXECUTIVE", null, none)));
        assertEquals(ReportQueries.Scope.farm("F1"), ReportQueries.Scope.of(new com.vinabeef.security.UserScope("m", "FARM_MANAGER", "F1", none)));
        assertTrue(ReportQueries.Scope.of(new com.vinabeef.security.UserScope("m", "FARM_MANAGER", null, none)).seesNothing());
        assertTrue(ReportQueries.Scope.of(new com.vinabeef.security.UserScope("s", "BARN_SUPERVISOR", "F1", none)).seesNothing());
        assertEquals(ReportQueries.Scope.barns(java.util.List.of("B1")),
                ReportQueries.Scope.of(new com.vinabeef.security.UserScope("s", "BARN_SUPERVISOR", "F1", java.util.List.of("B1"))));
        assertTrue(ReportQueries.Scope.of(new com.vinabeef.security.UserScope("x", "NONE", null, none)).seesNothing());
    }
}
