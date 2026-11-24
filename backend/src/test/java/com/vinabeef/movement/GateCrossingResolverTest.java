package com.vinabeef.movement;

import com.vinabeef.movement.GateCrossingResolver.Action;
import com.vinabeef.movement.GateCrossingResolver.Decision;
import com.vinabeef.movement.GateCrossingResolver.Known;
import com.vinabeef.movement.GateCrossingResolver.Side;
import com.vinabeef.movement.GateCrossingResolver.Sighting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules for one read at one reader of an inner/outer pair, from the movements the animal already has at the gate:
 * a read is always recorded (a HALF_CROSSING at first), the other reader completes it however much later it comes, the
 * order of the two reads is the direction, and the same reader again turns the direction around.
 */
class GateCrossingResolverTest {

    private static final long T = 1_795_164_000_000L;
    private static final long DEBOUNCE = 3_000L;

    private static Sighting seen(Side side, long ms) {
        return new Sighting(side, ms, "e-" + side + "-" + ms);
    }

    /** A half crossing: the animal was seen by one reader only. */
    private static Known half(String id, Side side, long ms) {
        return new Known(id, true, side, ms, ms);
    }

    /** A complete crossing from {@code startMs} to {@code atMs}. */
    private static Known complete(String id, long startMs, long atMs) {
        return new Known(id, false, null, startMs, atMs);
    }

    private static Decision decide(Known previous, Known next, Sighting incoming) {
        return GateCrossingResolver.decide(previous, next, incoming, DEBOUNCE);
    }

    // --- the first read -------------------------------------------------------------------------------------------

    @Test
    void aFirstReadIsRecordedAsAHalfCrossing() {
        Decision d = decide(null, null, seen(Side.INNER, T));
        assertEquals(Action.HALF, d.action());
        assertFalse(d.secondPass());
        assertNull(d.partner());
        assertNull(d.direction());
    }

    @Test
    void aHalfCrossingHasNoTimeLimit() {
        // the other reader sees the animal a minute, an hour and a week later: it still completes the crossing
        for (long gap : new long[]{31_693L, 60_000L, 3_600_000L, 7L * 24 * 3_600_000L}) {
            Decision d = decide(half("m1", Side.INNER, T), null, seen(Side.OUTER, T + gap));
            assertEquals(Action.COMPLETE, d.action(), "gap " + gap);
            assertEquals("OUT", d.direction());
        }
    }

    // --- the other reader: delta time is the direction ------------------------------------------------------------

    @Test
    void innerThenOuterMeansTheAnimalLeaves() {
        Decision d = decide(half("m1", Side.INNER, T), null, seen(Side.OUTER, T + 2_000));
        assertEquals(Action.COMPLETE, d.action());
        assertEquals("OUT", d.direction());
        assertEquals("m1", d.partner().movementId());
        assertTrue(d.partnerFirst());
    }

    @Test
    void outerThenInnerMeansTheAnimalReturns() {
        Decision d = decide(half("m1", Side.OUTER, T), null, seen(Side.INNER, T + 2_000));
        assertEquals(Action.COMPLETE, d.action());
        assertEquals("IN", d.direction());
        assertTrue(d.partnerFirst());
    }

    @Test
    void theRealBarnSequenceFromTheLogIsAReturn() {
        // outer reader C1C4 at 11:50:41.823 first, inner reader 0ED4 two seconds later
        Decision d = decide(half("m1", Side.OUTER, 41_823L), null, seen(Side.INNER, 43_900L));
        assertEquals("IN", d.direction());
    }

    @Test
    void aTwoSecondCrossingIsNotAnEchoEvenThoughItIsInsideTheRepeatTime() {
        // the repeat-read time (3 s) only merges reads at the SAME reader
        Decision d = decide(half("m1", Side.INNER, T), null, seen(Side.OUTER, T + 500));
        assertEquals(Action.COMPLETE, d.action());
    }

    // --- the same reader again ------------------------------------------------------------------------------------

    @Test
    void theSameReaderAgainInsideTheRepeatTimeIsOneTap() {
        assertEquals(Action.IGNORE, decide(half("m1", Side.INNER, T), null, seen(Side.INNER, T + 1_000)).action());
        assertEquals(Action.IGNORE, decide(half("m1", Side.INNER, T), null, seen(Side.INNER, T + 2_999)).action());
    }

    @Test
    void theSameReaderAgainAfterTheRepeatTimeIsASecondPass() {
        Decision d = decide(half("m1", Side.INNER, T), null, seen(Side.INNER, T + 3_000));
        assertEquals(Action.HALF, d.action());
        assertTrue(d.secondPass());
        // and it has no time limit either
        Decision later = decide(half("m1", Side.OUTER, T), null, seen(Side.OUTER, T + 86_400_000L));
        assertEquals(Action.HALF, later.action());
        assertTrue(later.secondPass());
    }

    @Test
    void aSecondPassIsNotASecondPassOfTheOtherReader() {
        // inner, then outer completes it, then outer again much later: that is a new first read, not a second pass
        Decision d = decide(complete("m1", T, T + 2_000), null, seen(Side.OUTER, T + 60_000));
        assertEquals(Action.HALF, d.action());
        assertFalse(d.secondPass());
    }

    // --- a crossing that is complete ------------------------------------------------------------------------------

    @Test
    void readsRightAfterACompleteCrossingAreTheAnimalWalkingAway() {
        Known done = complete("m1", T, T + 2_000);
        assertEquals(Action.IGNORE, decide(done, null, seen(Side.OUTER, T + 3_000)).action());
        assertEquals(Action.IGNORE, decide(done, null, seen(Side.INNER, T + 4_999)).action());
        assertEquals(Action.HALF, decide(done, null, seen(Side.OUTER, T + 5_000)).action());
    }

    @Test
    void aReadInsideACompleteCrossingBelongsToIt() {
        // a long crossing, with a read that arrives late from the edge backlog between its two reads
        Known done = complete("m1", T, T + 40_000);
        assertEquals(Action.IGNORE, decide(null, done, seen(Side.OUTER, T + 20_000)).action());
        assertEquals(Action.IGNORE, decide(null, done, seen(Side.INNER, T - 1_000)).action());
    }

    // --- reads that arrive late: placed by event time ---------------------------------------------------------------

    @Test
    void aLateReadCompletesTheHalfCrossingThatIsAfterIt() {
        // the outer read (T + 2 s) was processed first; the inner read from T arrives afterwards
        Decision d = decide(null, half("m1", Side.OUTER, T + 2_000), seen(Side.INNER, T));
        assertEquals(Action.COMPLETE, d.action());
        assertEquals("OUT", d.direction(), "inner first, then outer");
        assertFalse(d.partnerFirst());
    }

    @Test
    void aLateReadCompletesTheHalfCrossingBeforeItWhenThatIsTheOneThatIsOpen() {
        Decision d = decide(half("m1", Side.INNER, T), null, seen(Side.OUTER, T + 2_000));
        assertEquals("OUT", d.direction());
        assertTrue(d.partnerFirst());
    }

    @Test
    void theEarlierHalfCrossingWinsWhenBothSidesHaveOne() {
        Decision d = decide(half("before", Side.INNER, T), half("after", Side.INNER, T + 100_000), seen(Side.OUTER, T + 2_000));
        assertEquals("before", d.partner().movementId());
    }

    @Test
    void aLateEchoOfTheSameReaderIsIgnoredOnEitherSide() {
        assertEquals(Action.IGNORE, decide(null, half("m1", Side.INNER, T + 1_000), seen(Side.INNER, T)).action());
    }

    // --- a half crossing that can no longer be completed ------------------------------------------------------------

    @Test
    void aHalfCrossingOfAnOldReaderPairIsNeverCompleted() {
        // the readers of the gate were changed: that half crossing has no side any more
        Known abandoned = new Known("m0", true, null, T, T);
        Decision d = decide(abandoned, null, seen(Side.OUTER, T + 5_000));
        assertEquals(Action.HALF, d.action());
        assertFalse(d.secondPass());
    }

    @Test
    void sideParsingAcceptsAnyCaseAndRejectsOtherValues() {
        assertEquals(Side.INNER, Side.parse("inner"));
        assertEquals(Side.OUTER, Side.parse(" OUTER "));
        assertNull(Side.parse(null));
        assertNull(Side.parse("middle"));
    }
}
