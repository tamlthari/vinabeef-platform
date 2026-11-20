package com.vinabeef.movement;

import com.vinabeef.movement.GateCrossingResolver.Action;
import com.vinabeef.movement.GateCrossingResolver.Decision;
import com.vinabeef.movement.GateCrossingResolver.Side;
import com.vinabeef.movement.GateCrossingResolver.Sighting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The direction rule of a barn gate with two readers: inner reader first then outer reader means the animal
 * leaves, outer first then inner means it returns. No Spring context or database is needed.
 */
class GateCrossingResolverTest {

    private static final long WINDOW = 15_000;
    private static final long DEBOUNCE = 3_000;
    private static final long T0 = 1_795_164_000_000L;

    private static Sighting inner(long offsetMs, String id) {
        return new Sighting(Side.INNER, T0 + offsetMs, id);
    }

    private static Sighting outer(long offsetMs, String id) {
        return new Sighting(Side.OUTER, T0 + offsetMs, id);
    }

    private static Decision decide(List<Sighting> unpaired, Sighting incoming) {
        return GateCrossingResolver.decide(unpaired, incoming, false, WINDOW, DEBOUNCE);
    }

    @Test
    void aFirstSightingIsRememberedUntilTheOtherReaderSeesTheTag() {
        Decision d = decide(List.of(), outer(0, "e1"));
        assertEquals(Action.REMEMBER, d.action());
        assertNull(d.direction());
    }

    @Test
    void innerThenOuterMeansTheAnimalLeaves() {
        Decision d = decide(List.of(inner(0, "e1")), outer(2_000, "e2"));
        assertEquals(Action.COMPLETE, d.action());
        assertEquals("OUT", d.direction());
        assertEquals("e1", d.first().eventId());
        assertEquals("e2", d.later().eventId());
        assertEquals("e1", d.partner().eventId());
    }

    @Test
    void outerThenInnerMeansTheAnimalReturns() {
        Decision d = decide(List.of(outer(0, "e1")), inner(2_000, "e2"));
        assertEquals(Action.COMPLETE, d.action());
        assertEquals("IN", d.direction());
        assertEquals("e1", d.first().eventId());
    }

    @Test
    void theRealBarnSequenceFromTheLogIsAReturn() {
        // 044E6602AD1490 at DrC1C4 (outer) 08:37:17, then at DrC0F8 (inner) 08:37:19
        Decision d = decide(List.of(outer(0, "580306197")), inner(2_000, "580306198"));
        assertEquals("IN", d.direction());
    }

    @Test
    void theSameReaderAgainWithinTheDebounceIsOneTap() {
        Decision d = decide(List.of(outer(0, "e1")), outer(1_000, "e2"));
        assertEquals(Action.IGNORE, d.action());
    }

    @Test
    void theSameReaderAgainAfterTheDebounceIsRememberedToo() {
        Decision d = decide(List.of(outer(0, "e1")), outer(5_000, "e2"));
        assertEquals(Action.REMEMBER, d.action());
    }

    @Test
    void anAnimalLingeringAtTheOuterReaderThenWalkingThroughIsPairedWithTheNearestRead() {
        List<Sighting> pool = List.of(outer(0, "e1"), outer(16_000, "e2"), outer(27_000, "e3"));
        Decision d = decide(pool, inner(31_000, "e4"));
        assertEquals(Action.COMPLETE, d.action());
        assertEquals("IN", d.direction());
        assertEquals("e3", d.partner().eventId());
    }

    @Test
    void theOtherReaderAfterTheWindowDoesNotCompleteTheCrossing() {
        Decision d = decide(List.of(outer(0, "e1")), inner(60_000, "e2"));
        assertEquals(Action.REMEMBER, d.action());
        assertNull(d.direction());
    }

    @Test
    void aGapEqualToTheWindowStillCompletes() {
        Decision d = decide(List.of(inner(0, "e1")), outer(WINDOW, "e2"));
        assertEquals(Action.COMPLETE, d.action());
        assertEquals("OUT", d.direction());
    }

    @Test
    void aReadThatArrivesLateIsOrderedByHardwareTimeNotArrivalOrder() {
        // the outer read (t+2 s) was processed first, then the older inner read (t) arrives
        Decision d = decide(List.of(outer(2_000, "e2")), inner(0, "e1"));
        assertEquals(Action.COMPLETE, d.action());
        assertEquals("OUT", d.direction());
        assertEquals("e1", d.first().eventId());
        assertEquals("e2", d.later().eventId());
    }

    @Test
    void aLateReadStillFindsItsPartnerAfterNewerEventsHaveBeenProcessed() {
        // inner at +61 s waits in the pool; a much newer outer read (+120 s) came and went; now the outer read
        // from +59 s is retried. Arrival order must not lose the crossing.
        List<Sighting> pool = List.of(inner(61_000, "e104"), outer(120_000, "e107"));
        Decision d = decide(pool, outer(59_000, "e103"));
        assertEquals(Action.COMPLETE, d.action());
        assertEquals("IN", d.direction());
        assertEquals("e104", d.partner().eventId());
    }

    @Test
    void echoReadsRightAfterACompletedCrossingAreIgnored() {
        Decision d = GateCrossingResolver.decide(List.of(), outer(1_000, "e3"), true, WINDOW, DEBOUNCE);
        assertEquals(Action.IGNORE, d.action());
    }

    @Test
    void theNearestOppositeReadWinsWhenSeveralAreInTheWindow() {
        Decision d = decide(List.of(inner(0, "e1"), inner(9_000, "e2")), outer(10_000, "e3"));
        assertEquals("e2", d.partner().eventId());
        assertEquals("OUT", d.direction());
    }

    @Test
    void anEqualGapIsBrokenTheSameWayWhateverTheListOrder() {
        Sighting early = outer(6_500, "e4");
        Sighting late = outer(23_500, "e1");
        Decision a = decide(List.of(late, early), inner(15_000, "e6"));
        Decision b = decide(List.of(early, late), inner(15_000, "e6"));
        assertEquals("e4", a.partner().eventId());
        assertEquals("e4", b.partner().eventId());
    }

    @Test
    void sideParsingAcceptsAnyCaseAndRejectsOtherValues() {
        assertEquals(Side.INNER, Side.parse("inner"));
        assertEquals(Side.OUTER, Side.parse(" OUTER "));
        assertNull(Side.parse("middle"));
        assertNull(Side.parse(null));
    }
}
