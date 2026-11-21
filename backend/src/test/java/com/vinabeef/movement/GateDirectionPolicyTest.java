package com.vinabeef.movement;

import com.vinabeef.facilities.GateDirectionMode;
import com.vinabeef.movement.GateDirectionPolicy.HeadcountMove;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The direction rule of a gate: IN_ONLY and OUT_ONLY gates fix the direction, BIDIRECTIONAL gates measure it
 * (two readers) or toggle it by presence (one reader). Plus the headcount consequence of a crossing.
 */
class GateDirectionPolicyTest {

    private static final String B1 = "BARN-1";
    private static final String B2 = "BARN-2";

    // ---- which method decides the direction ------------------------------------------------------------

    @Test
    void bidirectionalGateWithTwoReadersUsesDeltaTime() {
        assertEquals("PAIRED_READERS", GateDirectionPolicy.methodFor(GateDirectionMode.BIDIRECTIONAL, true));
    }

    @Test
    void bidirectionalGateWithOneReaderTogglesByPresence() {
        assertEquals("GATE_MODE", GateDirectionPolicy.methodFor(GateDirectionMode.BIDIRECTIONAL, false));
    }

    @Test
    void oneWayGateIgnoresReaderSides() {
        // an IN_ONLY / OUT_ONLY gate never uses delta time, even when its readers are marked INNER / OUTER
        assertEquals("ONE_WAY_GATE", GateDirectionPolicy.methodFor(GateDirectionMode.IN_ONLY, true));
        assertEquals("ONE_WAY_GATE", GateDirectionPolicy.methodFor(GateDirectionMode.OUT_ONLY, true));
        assertEquals("ONE_WAY_GATE", GateDirectionPolicy.methodFor(GateDirectionMode.IN_ONLY, false));
        assertEquals("ONE_WAY_GATE", GateDirectionPolicy.methodFor(GateDirectionMode.OUT_ONLY, false));
    }

    // ---- direction of a single read --------------------------------------------------------------------

    @Test
    void inOnlyGateAlwaysMeansReturning() {
        for (String presence : new String[]{"IN_BARN", "OUT_PASTURE", null}) {
            assertEquals("IN", GateDirectionPolicy.singleReadDirection(GateDirectionMode.IN_ONLY, presence));
        }
    }

    @Test
    void outOnlyGateAlwaysMeansLeaving() {
        for (String presence : new String[]{"IN_BARN", "OUT_PASTURE", null}) {
            assertEquals("OUT", GateDirectionPolicy.singleReadDirection(GateDirectionMode.OUT_ONLY, presence));
        }
    }

    @Test
    void bidirectionalSingleReaderToggles() {
        assertEquals("IN", GateDirectionPolicy.singleReadDirection(GateDirectionMode.BIDIRECTIONAL, "OUT_PASTURE"));
        assertEquals("OUT", GateDirectionPolicy.singleReadDirection(GateDirectionMode.BIDIRECTIONAL, "IN_BARN"));
    }

    @Test
    void presenceFollowsTheDirection() {
        assertEquals("IN_BARN", GateDirectionPolicy.presenceAfter("IN"));
        assertEquals("OUT_PASTURE", GateDirectionPolicy.presenceAfter("OUT"));
    }

    // ---- headcount -------------------------------------------------------------------------------------

    @Test
    void returningAnimalAddsOneToTheGateBarn() {
        assertEquals(List.of(new HeadcountMove(B1, 1)), GateDirectionPolicy.headcountMoves("IN", "OUT_PASTURE", B1, B1));
    }

    @Test
    void leavingAnimalRemovesOneFromItsBarn() {
        assertEquals(List.of(new HeadcountMove(B1, -1)), GateDirectionPolicy.headcountMoves("OUT", "IN_BARN", B1, B1));
    }

    @Test
    void secondInReadWhileAlreadyInChangesNothing() {
        assertTrue(GateDirectionPolicy.headcountMoves("IN", "IN_BARN", B1, B1).isEmpty());
    }

    @Test
    void outReadForAnimalThatWasNeverInsideChangesNothing() {
        // an OUT_ONLY gate sees an animal that was never counted into the barn: the headcount must not go negative
        assertTrue(GateDirectionPolicy.headcountMoves("OUT", "OUT_PASTURE", B1, B1).isEmpty());
        assertTrue(GateDirectionPolicy.headcountMoves("OUT", "OUT_PASTURE", B2, B1).isEmpty());
    }

    @Test
    void returningThroughAnotherBarnMovesTheCountBetweenBarns() {
        assertEquals(List.of(new HeadcountMove(B1, -1), new HeadcountMove(B2, 1)),
                GateDirectionPolicy.headcountMoves("IN", "IN_BARN", B1, B2));
    }

    @Test
    void leavingFromAnotherBarnRemovesTheAnimalWhereItWasCounted() {
        assertEquals(List.of(new HeadcountMove(B1, -1)), GateDirectionPolicy.headcountMoves("OUT", "IN_BARN", B1, B2));
    }

    @Test
    void unknownPreviousBarnIsTolerated() {
        assertEquals(List.of(new HeadcountMove(B1, -1)), GateDirectionPolicy.headcountMoves("OUT", "IN_BARN", null, B1));
        assertTrue(GateDirectionPolicy.headcountMoves("IN", "IN_BARN", null, B1).isEmpty());
    }

    // ---- mode parsing ----------------------------------------------------------------------------------

    @Test
    void modeParsingAcceptsCaseAndSeparators() {
        assertEquals(GateDirectionMode.IN_ONLY, GateDirectionMode.tryParse(" in-only ").orElseThrow());
        assertEquals(GateDirectionMode.OUT_ONLY, GateDirectionMode.tryParse("out only").orElseThrow());
        assertEquals(GateDirectionMode.BIDIRECTIONAL, GateDirectionMode.tryParse("bidirectional").orElseThrow());
    }

    @Test
    void modeParsingRejectsUnknownInputButStoredValuesDefaultToBidirectional() {
        assertTrue(GateDirectionMode.tryParse("SIDEWAYS").isEmpty());
        assertTrue(GateDirectionMode.tryParse(null).isEmpty());
        assertTrue(GateDirectionMode.tryParse("").isEmpty());
        assertEquals(GateDirectionMode.BIDIRECTIONAL, GateDirectionMode.parseOrDefault("SIDEWAYS"));
        assertEquals(GateDirectionMode.BIDIRECTIONAL, GateDirectionMode.parseOrDefault(null));
    }

    @Test
    void oneWayFlagAndFixedDirection() {
        assertFalse(GateDirectionMode.BIDIRECTIONAL.isOneWay());
        assertTrue(GateDirectionMode.IN_ONLY.isOneWay());
        assertTrue(GateDirectionMode.OUT_ONLY.isOneWay());
        assertNull(GateDirectionMode.BIDIRECTIONAL.fixedDirection());
        assertEquals("IN", GateDirectionMode.IN_ONLY.fixedDirection());
        assertEquals("OUT", GateDirectionMode.OUT_ONLY.fixedDirection());
    }
}
