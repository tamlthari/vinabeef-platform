package com.vinabeef.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What every {@link TelemetryEventProcessor} must do, whoever implements it. The ingress routes (direct MQTT,
 * edge batch, dead-letter replay, the gate simulator) all rely on exactly this and nothing more, so an implementation
 * that passes can stand behind any of them.
 *
 * <p>To check a new implementation, extend this class and say how to build one and how to count what it stored.
 */
abstract class TelemetryEventProcessorContract {

    /** A processor that has stored nothing yet. */
    abstract TelemetryEventProcessor newProcessor();

    /** How many distinct events {@code processor} has stored. */
    abstract int storedEvents(TelemetryEventProcessor processor);

    static SafetrustEventDto event(long key, long timestamp) {
        SafetrustEventDto dto = new SafetrustEventDto();
        dto.setEventKey(key);
        dto.setTimestamp(timestamp);
        return dto;
    }

    @Test
    void anEventThatReturnedNormallyHasBeenStored() {
        TelemetryEventProcessor p = newProcessor();
        p.processSafetrustRead(event(1, 1000), "DIRECT_MQTT", System.nanoTime());
        assertEquals(1, storedEvents(p));
    }

    @Test
    void theSameEventTwiceIsStoredOnce() {
        TelemetryEventProcessor p = newProcessor();
        SafetrustEventDto e = event(7, 5000);
        p.processSafetrustRead(e, "DIRECT_MQTT", System.nanoTime());
        p.processSafetrustRead(e, "EDGE_BATCH", System.nanoTime());
        assertEquals(1, storedEvents(p));
    }

    @Test
    void theSameEventKeyFromAnotherRouteIsStillTheSameEvent() {
        TelemetryEventProcessor p = newProcessor();
        p.processSafetrustRead(event(9, 100), "DIRECT_MQTT", System.nanoTime());
        p.processSafetrustRead(event(9, 100), "PYTHON_ADAPTER", System.nanoTime());
        p.processSafetrustRead(event(9, 100), "GATE_REST", System.nanoTime());
        assertEquals(1, storedEvents(p));
    }

    @Test
    void differentEventsAreEachStored() {
        TelemetryEventProcessor p = newProcessor();
        p.processSafetrustRead(event(1, 1000), "EDGE_BATCH", System.nanoTime());
        p.processSafetrustRead(event(2, 2000), "EDGE_BATCH", System.nanoTime());
        p.processSafetrustRead(event(3, 3000), "EDGE_BATCH", System.nanoTime());
        assertEquals(3, storedEvents(p));
    }

    @Test
    void replayingABatchAgainChangesNothing() {
        TelemetryEventProcessor p = newProcessor();
        for (int round = 0; round < 2; round++) {
            for (long k = 1; k <= 4; k++) {
                p.processSafetrustRead(event(k, k * 1000), "EDGE_BATCH", System.nanoTime());
            }
        }
        assertEquals(4, storedEvents(p));
    }
}
