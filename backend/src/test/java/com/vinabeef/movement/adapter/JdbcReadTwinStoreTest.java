package com.vinabeef.movement.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcReadTwinStoreTest {

    private static final long T = 1_795_164_000_000L;

    @Test
    void lockingTakesTheTagAndReaderLock() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcReadTwinStore(jdbc).lock("044E6602AD1490", "DrC0F8");
        assertEquals(List.of("LOCK"), jdbc.calls);
        assertEquals("xsrc|044E6602AD1490|DrC0F8", jdbc.lastArgs.get("LOCK")[0]);
    }

    @Test
    void theTwinIsLookedForInTheWindowAroundTheReadOnTheOtherRoute() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("FIRST");
        List<String> found = new JdbcReadTwinStore(jdbc).findTwin("SECOND", "DrC0F8", "044E6602AD1490", "LOCAL_USB", T, 7_000);
        assertEquals(List.of("FIRST"), found);
        Object[] a = jdbc.lastArgs.get("FIND_TWIN");
        assertEquals("LOCAL_USB", a[2]);                  // the candidate must come from the other route
        assertEquals(T - 7_000, a[4]);
        assertEquals(T + 7_000, a[5]);
    }

    @Test
    void markingThePrimaryIsTrueOnlyWhenARowWasUpdated() {
        ScriptedJdbc one = new ScriptedJdbc().answerUpdate(1);
        assertTrue(new JdbcReadTwinStore(one).markPrimary("FIRST", "SECOND"));
        assertEquals(List.<Object>of("SECOND", "FIRST"), List.of(one.lastArgs.get("MARK_PRIMARY")));

        ScriptedJdbc none = new ScriptedJdbc().answerUpdate(0);
        assertFalse(new JdbcReadTwinStore(none).markPrimary("FIRST", "SECOND"));
    }

    @Test
    void theCopyPointsAtTheFirstRead() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcReadTwinStore(jdbc).markDuplicate("SECOND", "FIRST");
        assertEquals(List.<Object>of("FIRST", "SECOND"), List.of(jdbc.lastArgs.get("MARK_DUPLICATE")));
    }
}
