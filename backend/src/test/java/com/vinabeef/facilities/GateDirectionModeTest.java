package com.vinabeef.facilities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What every gate direction mode must satisfy, over {@code values()}. */
class GateDirectionModeTest {

    @Test
    void aModeIsOneWayExactlyWhenItFixesTheDirection() {
        for (GateDirectionMode mode : GateDirectionMode.values()) {
            assertEquals(mode.fixedDirection() != null, mode.isOneWay(), mode.toString());
            if (mode.fixedDirection() != null) {
                assertTrue(mode.fixedDirection().equals("IN") || mode.fixedDirection().equals("OUT"), mode.toString());
            }
        }
    }

    @Test
    void everyModeIsFoundByItsNameInAnyCaseWithDashOrSpace() {
        for (GateDirectionMode mode : GateDirectionMode.values()) {
            assertSame(mode, GateDirectionMode.tryParse(mode.name()).orElseThrow());
            assertSame(mode, GateDirectionMode.tryParse(" " + mode.name().toLowerCase().replace('_', '-') + " ").orElseThrow());
            assertSame(mode, GateDirectionMode.tryParse(mode.name().toLowerCase().replace('_', ' ')).orElseThrow());
            assertSame(mode, GateDirectionMode.parseOrDefault(mode.name()));
        }
    }

    @Test
    void aMissingOrUnknownStoredValueHasAlwaysMeantBidirectional() {
        assertSame(GateDirectionMode.BIDIRECTIONAL, GateDirectionMode.parseOrDefault(null));
        assertSame(GateDirectionMode.BIDIRECTIONAL, GateDirectionMode.parseOrDefault(""));
        assertSame(GateDirectionMode.BIDIRECTIONAL, GateDirectionMode.parseOrDefault("sideways"));
        assertFalse(GateDirectionMode.tryParse("sideways").isPresent());
        assertFalse(GateDirectionMode.tryParse(null).isPresent());
    }

    @Test
    void theOneWayModesFixTheirDirection() {
        assertEquals("IN", GateDirectionMode.IN_ONLY.fixedDirection());
        assertEquals("OUT", GateDirectionMode.OUT_ONLY.fixedDirection());
        assertEquals(null, GateDirectionMode.BIDIRECTIONAL.fixedDirection());
    }
}
