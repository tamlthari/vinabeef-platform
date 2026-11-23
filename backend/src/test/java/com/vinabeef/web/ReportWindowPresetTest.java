package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What every named period must satisfy, over {@code values()}: a new constant is held to the same rules. */
class ReportWindowPresetTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant NOW = Instant.parse("2026-11-22T13:40:00Z");

    @Test
    void everyPeriodStartsBeforeItEndsAndEndsJustAfterNow() {
        for (ReportWindow.Preset preset : ReportWindow.Preset.values()) {
            ReportWindow.Window w = preset.window(VN, NOW);
            assertTrue(w.fromInclusive().isBefore(w.toExclusive()), preset + " is empty");
            assertEquals(NOW.plusSeconds(1), w.toExclusive(), preset + " must include the present moment");
        }
    }

    @Test
    void everyPeriodHasALabelAndAUniqueLowerCaseKey() {
        Set<String> keys = new HashSet<>();
        for (ReportWindow.Preset preset : ReportWindow.Preset.values()) {
            assertFalse(preset.label().isBlank(), preset.toString());
            assertEquals(preset.key().toLowerCase(), preset.key(), preset.toString());
            assertTrue(keys.add(preset.key()), "duplicate key " + preset.key());
        }
    }

    @Test
    void everyPeriodIsFoundByItsKeyWhateverTheCaseOrSpaces() {
        for (ReportWindow.Preset preset : ReportWindow.Preset.values()) {
            assertSame(preset, ReportWindow.Preset.of(preset.key()));
            assertSame(preset, ReportWindow.Preset.of("  " + preset.key().toUpperCase() + " "));
            assertEquals(preset.window(VN, NOW), ReportWindow.preset(preset.key(), VN, NOW));
        }
    }

    @Test
    void aMissingOrUnknownNameMeansToday() {
        assertSame(ReportWindow.Preset.TODAY, ReportWindow.Preset.of(null));
        assertSame(ReportWindow.Preset.TODAY, ReportWindow.Preset.of(""));
        assertSame(ReportWindow.Preset.TODAY, ReportWindow.Preset.of("last fortnight"));
    }

    @Test
    void allTimeStartsAtTheEpochAndTheDayPeriodsStartAtLocalMidnight() {
        assertEquals(Instant.EPOCH, ReportWindow.Preset.ALL_TIME.window(VN, NOW).fromInclusive());
        Instant midnight = Instant.parse("2026-11-21T17:00:00Z"); // 00:00 on 22 November in Vietnam
        assertEquals(midnight, ReportWindow.Preset.TODAY.window(VN, NOW).fromInclusive());
        assertEquals(midnight.minusSeconds(6 * 86400L), ReportWindow.Preset.LAST_7_DAYS.window(VN, NOW).fromInclusive());
        assertEquals(midnight.minusSeconds(29 * 86400L), ReportWindow.Preset.LAST_30_DAYS.window(VN, NOW).fromInclusive());
        assertEquals(NOW.minusSeconds(86400L), ReportWindow.Preset.LAST_24_HOURS.window(VN, NOW).fromInclusive());
    }
}
