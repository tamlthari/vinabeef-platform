package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every report type must satisfy, tested over {@code values()} so that a new constant is held to the same rules
 * without touching this class.
 */
class SurveillanceReportTypeTest {

    private static int placeholders(String sql) {
        return (int) sql.chars().filter(c -> c == '?').count();
    }

    @Test
    void everyTypeDeclaresTheNumberOfParametersItsStatementTakes() {
        for (SurveillanceReportType type : SurveillanceReportType.values()) {
            assertEquals(placeholders(type.sql()), type.farmParams(), type + " declares a wrong parameter count");
        }
    }

    @Test
    void everyTypeIsLimitedSoACutCanBeReported() {
        for (SurveillanceReportType type : SurveillanceReportType.values()) {
            assertTrue(type.sql().endsWith(" LIMIT " + (SurveillanceQueries.MAX_ROWS + 1)), type.toString());
            assertFalse(type.select().contains("LIMIT"), type + " must leave the limit to sql()");
        }
    }

    @Test
    void everyTypeReadsOnlyAndFiltersByFarm() {
        for (SurveillanceReportType type : SurveillanceReportType.values()) {
            String sql = type.sql().trim().toUpperCase();
            assertTrue(sql.startsWith("SELECT"), type.toString());
            assertTrue(sql.contains("? = TRUE"), type + " must be restrictable to one farm");
        }
    }

    @Test
    void everyTypeIsFoundByItsOwnNameAndThroughTheQueriesFacade() {
        for (SurveillanceReportType type : SurveillanceReportType.values()) {
            assertSame(type, SurveillanceReportType.tryParse(type.name()).orElseThrow());
            assertTrue(SurveillanceQueries.isKnownType(type.name()));
            assertEquals(type.sql(), SurveillanceQueries.report(type.name()));
            assertEquals(type.farmParams(), SurveillanceQueries.farmParams(type.name()));
        }
    }

    @Test
    void onlyTheExactNameIsAReport() {
        assertFalse(SurveillanceQueries.isKnownType("movement_transfers"));
        assertFalse(SurveillanceQueries.isKnownType(" MOVEMENT_TRANSFERS"));
        assertFalse(SurveillanceQueries.isKnownType(""));
        assertFalse(SurveillanceQueries.isKnownType("nope"));
        assertFalse(SurveillanceQueries.isKnownType(null));
    }

    @Test
    void anUnknownNameStillGivesTheSummaryAsItAlwaysDid() {
        assertEquals(SurveillanceReportType.OUTBREAK_SURVEILLANCE.sql(), SurveillanceQueries.report("nope"));
        assertEquals(2, SurveillanceQueries.farmParams("nope"));
        assertSame(SurveillanceReportType.OUTBREAK_SURVEILLANCE, SurveillanceReportType.DEFAULT);
    }

    @Test
    void transfersTakeTheFarmTwice() {
        assertEquals(3, SurveillanceReportType.MOVEMENT_TRANSFERS.farmParams());
        assertEquals(3, SurveillanceQueries.farmParams("MOVEMENT_TRANSFERS"));
    }
}
