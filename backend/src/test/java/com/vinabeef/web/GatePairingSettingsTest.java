package com.vinabeef.web;

import com.vinabeef.web.GatePairingSettings.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The limits on a gate's repeat-read time. (There is no pairing window any more: a half crossing waits as long as it takes.) */
class GatePairingSettingsTest {

    @Test
    void aLeftOutValueKeepsTheCurrentSetting() {
        Result r = GatePairingSettings.resolve(3_000, null);
        assertTrue(r.ok());
        assertEquals(3_000, r.repeatDebounceMs());
        assertNull(r.error());
    }

    @Test
    void theRepeatTimeCanBeChanged() {
        Result r = GatePairingSettings.resolve(3_000, 5_000);
        assertTrue(r.ok());
        assertEquals(5_000, r.repeatDebounceMs());
    }

    @Test
    void theLimitsThemselvesAreAllowed() {
        assertTrue(GatePairingSettings.resolve(3_000, 500).ok());
        assertTrue(GatePairingSettings.resolve(3_000, 60_000).ok());
    }

    @Test
    void aRepeatTimeOutsideItsLimitsIsRejected() {
        Result tooShort = GatePairingSettings.resolve(3_000, 499);
        assertFalse(tooShort.ok());
        assertTrue(tooShort.error().contains("repeat-read time"), tooShort.error());
        assertTrue(tooShort.error().contains("between 0.5 s and 60 s"), tooShort.error());
        assertTrue(tooShort.error().contains("it was 0.499 s"), tooShort.error());
        assertFalse(GatePairingSettings.resolve(3_000, 60_001).ok());
    }

    @Test
    void aStoredValueThatIsOutOfRangeIsCaughtToo() {
        assertFalse(GatePairingSettings.resolve(100, null).ok());
    }

    @Test
    void secondsAreWrittenTheWayAPersonReadsThem() {
        assertEquals("15 s", GatePairingSettings.seconds(15_000));
        assertEquals("31.693 s", GatePairingSettings.seconds(31_693));
        assertEquals("0.5 s", GatePairingSettings.seconds(500));
        assertEquals("300 s", GatePairingSettings.seconds(300_000));
    }
}
