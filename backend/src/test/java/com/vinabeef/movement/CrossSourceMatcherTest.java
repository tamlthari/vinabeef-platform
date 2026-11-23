package com.vinabeef.movement;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order of statements the matcher runs and what it does with each answer. The SQL itself is run on PostgreSQL elsewhere. */
class CrossSourceMatcherTest {

    private static final long T = 1_795_164_000_000L;

    private static CrossSourceMatcher matcher(ScriptedJdbc jdbc, boolean enabled, long window) {
        return new CrossSourceMatcher(jdbc, enabled, window);
    }

    @Test
    void aTapSeenTwiceMarksTheSecondReadAsTheCopyOfTheFirst() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("FIRST");
        Optional<String> first = matcher(jdbc, true, 10_000).claimFirstCopy("SECOND", "DrC0F8", "044E6602AD1490", "SAFETRUST", T);
        assertEquals(Optional.of("FIRST"), first);
        assertEquals(List.of("LOCK", "FIND_TWIN", "MARK_PRIMARY", "MARK_DUPLICATE"), jdbc.calls);
        assertEquals(List.<Object>of("SECOND", "FIRST"), List.of(jdbc.lastArgs.get("MARK_PRIMARY")));
    }

    @Test
    void aReadWithNoCopyIsLeftAloneAndLooksInTheWindowAroundItsTime() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        Optional<String> first = matcher(jdbc, true, 7_000).claimFirstCopy("E1", "DrC0F8", "044E6602AD1490", "LOCAL_USB", T);
        assertFalse(first.isPresent());
        assertEquals(List.of("LOCK", "FIND_TWIN"), jdbc.calls);
        Object[] a = jdbc.lastArgs.get("FIND_TWIN");
        assertEquals("LOCAL_USB", a[2]);                  // the candidate must come from the other route
        assertEquals(T - 7_000, a[4]);
        assertEquals(T + 7_000, a[5]);
    }

    @Test
    void ifTheFirstCopyWasPairedMeanwhileTheReadIsNotMarkedAsACopy() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("FIRST").answerUpdate(0);
        Optional<String> first = matcher(jdbc, true, 10_000).claimFirstCopy("SECOND", "DrC0F8", "044E6602AD1490", "SAFETRUST", T);
        assertFalse(first.isPresent());
        assertEquals(List.of("LOCK", "FIND_TWIN", "MARK_PRIMARY"), jdbc.calls);
    }

    @Test
    void switchedOffOrIncompleteReadsRunNoStatementAtAll() {
        ScriptedJdbc off = new ScriptedJdbc().answerList("FIRST");
        assertFalse(matcher(off, false, 10_000).claimFirstCopy("E", "DrC0F8", "CARD", "SAFETRUST", T).isPresent());
        assertTrue(off.calls.isEmpty());

        ScriptedJdbc noCard = new ScriptedJdbc();
        assertFalse(matcher(noCard, true, 10_000).claimFirstCopy("E", "DrC0F8", null, "SAFETRUST", T).isPresent());
        assertFalse(matcher(noCard, true, 10_000).claimFirstCopy("E", null, "CARD", "SAFETRUST", T).isPresent());
        assertTrue(noCard.calls.isEmpty());
    }

    @Test
    void aNegativeWindowIsTreatedAsZero() {
        assertEquals(0L, matcher(new ScriptedJdbc(), true, -5).windowMs());
    }
}
