package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurveillanceQueriesTest {

    private static int marks(String sql) {
        int n = 0;
        for (char c : sql.toCharArray()) if (c == '?') n++;
        return n;
    }

    @Test
    void everyReportDeclaresItsParameterCount() {
        for (String type : new String[]{"OUTBREAK_SURVEILLANCE", "MOVEMENT_TRANSFERS", "HEALTH_TREATMENTS", "VACCINATION_COVERAGE"}) {
            assertEquals(SurveillanceQueries.farmParams(type), marks(SurveillanceQueries.report(type)), type);
            assertTrue(SurveillanceQueries.isKnownType(type));
        }
        assertFalse(SurveillanceQueries.isKnownType("DROP"));
    }

    @Test
    void reportsFetchOneRowBeyondTheCapSoACutCanBeReported() {
        assertTrue(SurveillanceQueries.report("HEALTH_TREATMENTS").contains("LIMIT " + (SurveillanceQueries.MAX_ROWS + 1)));
        assertEquals(50_000, SurveillanceQueries.MAX_ROWS);
    }

    @Test
    void theOutbreakReportNoLongerClaimsFmdLsdForAnyVaccine() {
        String sql = SurveillanceQueries.report("OUTBREAK_SURVEILLANCE");
        assertTrue(sql.contains("vaccinated_any_count"));
        assertTrue(sql.contains("fmd_vaccinated_count"));
        assertTrue(sql.contains("lsd_vaccinated_count"));
        assertFalse(sql.contains("fmd_lsd_vaccinated_count"));
    }

    @Test
    void healthReportCountsActiveAnimalsOnlyLikeTheDashboard() {
        assertTrue(SurveillanceQueries.report("HEALTH_TREATMENTS").contains("a.lifecycle_status = 'ACTIVE'"));
    }

    @Test
    void everyCheckTakesTheFarmFilterAndHasASeverity() {
        var checks = SurveillanceQueries.checks();
        assertEquals(7, checks.size());
        for (var c : checks.values()) {
            assertEquals(2, marks(c.sql()), c.code());
            assertTrue(c.severity().equals("error") || c.severity().equals("warning") || c.severity().equals("info"), c.code());
            assertTrue(c.sql().startsWith("SELECT COUNT(*)"), c.code());
        }
    }
}
