package com.vinabeef.web;

import com.vinabeef.web.GatePairingSettings.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The limits on a gate's pairing window and repeat-read time. */
class GatePairingSettingsTest {

    @Test
    void leftOutValuesKeepTheCurrentSettings() {
        Result r = GatePairingSettings.resolve(15_000, 3_000, null, null);
        assertTrue(r.ok());
        assertEquals(15_000, r.pairWindowMs());
        assertEquals(3_000, r.repeatDebounceMs());
        assertNull(r.error());
    }

    @Test
    void aWindowLongEnoughForTheSlowCrossingInTheReportIsAccepted() {
        // inner 11:50:10.130, outer 11:50:41.823: 31.7 s apart, more than the 15 s default
        Result r = GatePairingSettings.resolve(15_000, 3_000, 60_000, null);
        assertTrue(r.ok());
        assertEquals(60_000, r.pairWindowMs());
        assertEquals(3_000, r.repeatDebounceMs());
    }

    @Test
    void onlyTheRepeatTimeCanBeChangedToo() {
        Result r = GatePairingSettings.resolve(15_000, 3_000, null, 5_000);
        assertTrue(r.ok());
        assertEquals(15_000, r.pairWindowMs());
        assertEquals(5_000, r.repeatDebounceMs());
    }

    @Test
    void theLimitsThemselvesAreAllowed() {
        assertTrue(GatePairingSettings.resolve(15_000, 3_000, 1_000, 500).ok());
        assertTrue(GatePairingSettings.resolve(15_000, 3_000, 300_000, 60_000).ok());
    }

    @Test
    void aWindowBelowOneSecondOrAboveFiveMinutesIsRejected() {
        Result tooShort = GatePairingSettings.resolve(15_000, 500, 999, null);
        assertFalse(tooShort.ok());
        assertTrue(tooShort.error().contains("between 1 s and 300 s"), tooShort.error());
        assertTrue(tooShort.error().contains("it was 0.999 s"), tooShort.error());

        Result tooLong = GatePairingSettings.resolve(15_000, 3_000, 300_001, null);
        assertFalse(tooLong.ok());
        assertTrue(tooLong.error().contains("pairing window"), tooLong.error());
    }

    @Test
    void aRepeatTimeOutsideItsLimitsIsRejected() {
        assertFalse(GatePairingSettings.resolve(15_000, 3_000, null, 499).ok());
        Result tooLong = GatePairingSettings.resolve(300_000, 3_000, null, 60_001);
        assertFalse(tooLong.ok());
        assertTrue(tooLong.error().contains("repeat-read time"), tooLong.error());
    }

    @Test
    void theWindowCannotBeShorterThanTheRepeatTime() {
        Result r = GatePairingSettings.resolve(15_000, 3_000, 2_000, null);
        assertFalse(r.ok());
        assertTrue(r.error().contains("cannot be shorter"), r.error());
        // a stored repeat time that is longer than a requested window is caught the same way
        assertFalse(GatePairingSettings.resolve(15_000, 10_000, 5_000, null).ok());
        // and raising the repeat time above the stored window is too
        assertFalse(GatePairingSettings.resolve(15_000, 3_000, null, 20_000).ok());
    }

    @Test
    void secondsAreWrittenTheWayAPersonReadsThem() {
        assertEquals("15 s", GatePairingSettings.seconds(15_000));
        assertEquals("31.693 s", GatePairingSettings.seconds(31_693));
        assertEquals("0.5 s", GatePairingSettings.seconds(500));
        assertEquals("300 s", GatePairingSettings.seconds(300_000));
    }
}
