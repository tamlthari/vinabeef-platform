package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportWindowTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    /** 2026-11-21 22:23 in Vietnam = 15:23 UTC the same day. */
    private static final Instant NOW = Instant.parse("2026-11-21T15:23:00Z");

    @Test
    void todayStartsAtLocalMidnightNotUtcMidnight() {
        ReportWindow.Window w = ReportWindow.preset("today", VN, NOW);

        assertEquals(Instant.parse("2026-11-20T17:00:00Z"), w.fromInclusive());
        assertTrue(w.toExclusive().isAfter(NOW));
    }

    @Test
    void aPassAtHalfPastSixLocalBelongsToThatLocalDay() {
        Instant earlyMorningVn = Instant.parse("2026-11-20T23:30:00Z");   // 06:30 on 21 November in Vietnam
        ReportWindow.Window w = ReportWindow.preset("today", VN, NOW);

        assertTrue(!earlyMorningVn.isBefore(w.fromInclusive()));
    }

    @Test
    void sevenDaysIsSixFullDaysPlusToday() {
        ReportWindow.Window w = ReportWindow.preset("7d", VN, NOW);

        assertEquals(Instant.parse("2026-11-14T17:00:00Z"), w.fromInclusive());
    }

    @Test
    void unknownPresetMeansToday() {
        assertEquals(ReportWindow.preset("today", VN, NOW), ReportWindow.preset("whatever", VN, NOW));
        assertEquals(ReportWindow.preset("today", VN, NOW), ReportWindow.preset(null, VN, NOW));
    }

    @Test
    void aDateOnlyEndIncludesThatWholeDay() {
        ReportWindow.Window w = ReportWindow.custom("2026-11-01", "2026-11-03", VN, NOW);

        assertEquals(Instant.parse("2026-10-31T17:00:00Z"), w.fromInclusive());
        assertEquals(Instant.parse("2026-11-03T17:00:00Z"), w.toExclusive());
    }

    @Test
    void aTimedEndIncludesTheMinuteThatWasTyped() {
        ReportWindow.Window w = ReportWindow.custom("2026-11-21 08:00", "2026-11-21 09:30", VN, NOW);

        assertEquals(Instant.parse("2026-11-21T01:00:00Z"), w.fromInclusive());
        assertEquals(Instant.parse("2026-11-21T02:31:00Z"), w.toExclusive());
    }

    @Test
    void typedDatesWinOverThePreset() {
        ReportWindow.Window w = ReportWindow.resolve("24h", "2026-11-01", "", VN, NOW);

        assertEquals("Custom period", w.label());
        assertEquals(Instant.parse("2026-10-31T17:00:00Z"), w.fromInclusive());
    }

    @Test
    void anEndBeforeTheStartIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ReportWindow.custom("2026-11-05", "2026-11-01", VN, NOW));
    }

    @Test
    void garbageDatesAreRejectedWithAReadableMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ReportWindow.custom("tomorrow", null, VN, NOW));

        assertTrue(e.getMessage().contains("yyyy-MM-dd"));
    }
}
