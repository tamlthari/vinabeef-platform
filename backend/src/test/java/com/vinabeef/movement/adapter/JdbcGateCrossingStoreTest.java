package com.vinabeef.movement.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcGateCrossingStoreTest {

    private static final long T = 1_795_164_000_000L;

    @Test
    void aMissingReaderNameAsksNothing() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        JdbcGateCrossingStore store = new JdbcGateCrossingStore(jdbc);
        assertEquals(Optional.empty(), store.findReader(null));
        store.touchReader(null, T);
        store.setConnection(null, "ONLINE", "10.0.0.5", T);
        assertTrue(jdbc.calls.isEmpty());
    }

    @Test
    void aRepeatReadIsNeverNearWhenThereIsNoDebounceTime() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerCount(5);
        assertFalse(new JdbcGateCrossingStore(jdbc).movementNear("A-1", "G-1", T, 0));
        assertTrue(jdbc.calls.isEmpty());
    }

    @Test
    void aMovementNearIsAnyMovementOfTheAnimalAtTheGateInsideTheDebounceOnEitherSide() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerCount(1);
        assertTrue(new JdbcGateCrossingStore(jdbc).movementNear("A-1", "G-1", T, 3_000));
        Object[] a = jdbc.lastArgs.get("MOVEMENT_NEAR");
        assertEquals("A-1", a[0]);
        assertEquals("G-1", a[1]);
        assertEquals(new java.sql.Timestamp(T - 3_000), a[2]);
        assertEquals(new java.sql.Timestamp(T + 3_000), a[3]);
    }

    @Test
    void theNeighboursAreAskedForWithOneStatementWithTheEventTimeOnBothSides() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        var around = new JdbcGateCrossingStore(jdbc).neighbours("A-1", "G-1", T);
        assertEquals(List.of("NEIGHBOURS"), jdbc.calls);
        Object[] a = jdbc.lastArgs.get("NEIGHBOURS");
        assertEquals("A-1", a[0]);
        assertEquals("G-1", a[1]);
        assertEquals(new java.sql.Timestamp(T), a[2]);
        assertEquals("A-1", a[3]);
        assertEquals("G-1", a[4]);
        assertEquals(new java.sql.Timestamp(T), a[5]);
        assertEquals(null, around.previous(), "no movement before");
        assertEquals(null, around.next(), "no movement after");
    }

    @Test
    void theLockKeyNamesTheTagAndTheGate() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcGateCrossingStore(jdbc).lockCrossing("TAG-1", "G-1");
        assertEquals("TAG-1|G-1", jdbc.lastArgs.get("LOCK")[0]);
    }

    @Test
    void aReaderStatusChangeIsStoredWithItsEventTimeSoAnOlderEventCannotOverwriteANewerOne() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcGateCrossingStore(jdbc).setConnection("DrC0F8", "OFFLINE", null, T);
        Object[] a = jdbc.lastArgs.get("SET_CONNECTION");
        assertEquals("OFFLINE", a[0]);
        assertEquals("DrC0F8", a[3]);
        assertEquals(new java.sql.Timestamp(T), a[2]);
        assertEquals(new java.sql.Timestamp(T), a[4]);
    }
}
