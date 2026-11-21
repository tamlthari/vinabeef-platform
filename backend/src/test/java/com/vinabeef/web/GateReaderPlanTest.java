package com.vinabeef.web;

import com.vinabeef.facilities.GateDirectionMode;
import com.vinabeef.web.GateReaderPlan.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The consistency rules of a gate with one reader, an inner/outer pair, or a one-way direction. */
class GateReaderPlanTest {

    private static final GateDirectionMode BI = GateDirectionMode.BIDIRECTIONAL;

    @Test
    void aNewSingleReaderGateDefaultsToBidirectional() {
        Result r = GateReaderPlan.plan(null, null, null, "DrA1", null, null);
        assertTrue(r.ok());
        assertEquals("DrA1", r.target().primary());
        assertNull(r.target().outer());
        assertEquals(BI, r.target().mode());
        assertFalse(r.target().pairChanged());
    }

    @Test
    void aNewGateNeedsAReaderName() {
        Result r = GateReaderPlan.plan(null, null, null, "  ", null, "IN_ONLY");
        assertFalse(r.ok());
        assertEquals(400, r.status());
    }

    @Test
    void aNewPairedGateIsBidirectionalWithAnOuterReader() {
        Result r = GateReaderPlan.plan(null, null, null, "DrIn", "DrOut", "BIDIRECTIONAL");
        assertTrue(r.ok());
        assertEquals("DrOut", r.target().outer());
        assertTrue(r.target().pairChanged());
    }

    @Test
    void aOneWayGateCannotBeCreatedWithAnOuterReader() {
        for (String mode : new String[]{"IN_ONLY", "OUT_ONLY"}) {
            Result r = GateReaderPlan.plan(null, null, null, "DrIn", "DrOut", mode);
            assertFalse(r.ok());
            assertEquals(400, r.status());
        }
    }

    @Test
    void aOneWayGateWithOneReaderIsFine() {
        Result in = GateReaderPlan.plan(null, null, null, "DrIn", null, "IN_ONLY");
        Result out = GateReaderPlan.plan(null, null, null, "DrOut", "", "out_only");
        assertTrue(in.ok());
        assertEquals(GateDirectionMode.IN_ONLY, in.target().mode());
        assertTrue(out.ok());
        assertEquals(GateDirectionMode.OUT_ONLY, out.target().mode());
    }

    @Test
    void unknownDirectionModeIsRejectedWithTheValidValues() {
        Result r = GateReaderPlan.plan("DrA", null, BI, null, null, "SIDEWAYS");
        assertFalse(r.ok());
        assertEquals(400, r.status());
        assertTrue(r.error().contains("IN_ONLY"));
    }

    @Test
    void anEditThatSendsNothingChangesNothing() {
        Result r = GateReaderPlan.plan("DrIn", "DrOut", BI, null, null, null);
        assertTrue(r.ok());
        assertEquals("DrIn", r.target().primary());
        assertEquals("DrOut", r.target().outer());
        assertEquals(BI, r.target().mode());
        assertFalse(r.target().pairChanged());
    }

    @Test
    void switchingAPairedGateToOneWayIsRefusedUntilTheOuterReaderIsRemoved() {
        Result refused = GateReaderPlan.plan("DrIn", "DrOut", BI, null, null, "IN_ONLY");
        assertFalse(refused.ok());
        assertEquals(409, refused.status());
        assertTrue(refused.error().contains("DrOut"));

        Result accepted = GateReaderPlan.plan("DrIn", "DrOut", BI, null, "", "IN_ONLY");
        assertTrue(accepted.ok());
        assertNull(accepted.target().outer());
        assertEquals(GateDirectionMode.IN_ONLY, accepted.target().mode());
        assertTrue(accepted.target().pairChanged());
    }

    @Test
    void aOneWayGateCanBecomeBidirectionalAgainAndGainAPair() {
        Result r = GateReaderPlan.plan("DrIn", null, GateDirectionMode.IN_ONLY, null, "DrOut", "BIDIRECTIONAL");
        assertTrue(r.ok());
        assertEquals("DrOut", r.target().outer());
        assertTrue(r.target().pairChanged());
    }

    @Test
    void addingAnOuterReaderToAOneWayGateWithoutChangingTheModeIsRefused() {
        Result r = GateReaderPlan.plan("DrIn", null, GateDirectionMode.OUT_ONLY, null, "DrOut", null);
        assertFalse(r.ok());
        assertEquals(400, r.status());
    }

    @Test
    void theInnerAndOuterReaderMustDiffer() {
        Result r = GateReaderPlan.plan("DrIn", null, BI, null, "DrIn", null);
        assertFalse(r.ok());
        assertEquals(400, r.status());
        Result renamed = GateReaderPlan.plan("DrIn", "DrOut", BI, "DrOut", null, null);
        assertFalse(renamed.ok());
    }

    @Test
    void renamingTheReaderOfAPairIsAPairChangeButRenamingASingleReaderIsNot() {
        Result pair = GateReaderPlan.plan("DrIn", "DrOut", BI, "DrNew", null, null);
        assertTrue(pair.ok());
        assertTrue(pair.target().pairChanged());
        Result single = GateReaderPlan.plan("DrIn", null, BI, "DrNew", null, null);
        assertTrue(single.ok());
        assertFalse(single.target().pairChanged());
        assertEquals("DrNew", single.target().primary());
    }

    @Test
    void anUnchangedRepeatOfThePairIsNotAChange() {
        Result r = GateReaderPlan.plan("DrIn", "DrOut", BI, "DrIn", "DrOut", "BIDIRECTIONAL");
        assertTrue(r.ok());
        assertFalse(r.target().pairChanged());
    }
}
