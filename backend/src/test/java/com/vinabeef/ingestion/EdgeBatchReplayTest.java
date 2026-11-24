package com.vinabeef.ingestion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gateway's replay, run against stand-in processors: it needs nothing but the port. */
class EdgeBatchReplayTest {

    /** Records what it was given, in order, and throws for the keys it was told to refuse. */
    private static final class Recording implements TelemetryEventProcessor {
        final List<Long> keys = new ArrayList<>();
        final List<String> modes = new ArrayList<>();
        final List<Long> nanos = new ArrayList<>();
        private final Set<Long> refuse;

        Recording(Long... refuse) {
            this.refuse = Set.of(refuse);
        }

        @Override
        public void processSafetrustRead(SafetrustEventDto dto, String ingressMode, long tIngressNano) {
            keys.add(dto.getEventKey());
            modes.add(ingressMode);
            nanos.add(tIngressNano);
            if (refuse.contains(dto.getEventKey())) {
                throw new IllegalStateException("database down for " + dto.getEventKey());
            }
        }

        // The replay needs none of the unmapped-read operations; a stand-in that is asked for them has been wired wrongly.
        @Override
        public boolean reprocessUnmappedLocalRead(String eventId) {
            throw new UnsupportedOperationException("the replay never retries unmapped reads");
        }

        @Override
        public List<String> unmappedLocalReadIds(int limit) {
            throw new UnsupportedOperationException("the replay never lists unmapped reads");
        }

        @Override
        public int countUnmappedLocalReads() {
            throw new UnsupportedOperationException("the replay never counts unmapped reads");
        }
    }

    private static SafetrustEventDto event(Long key, Long timestamp) {
        SafetrustEventDto dto = new SafetrustEventDto();
        dto.setEventKey(key);
        dto.setTimestamp(timestamp);
        return dto;
    }

    @Test
    void eventsAreReplayedOldestReaderTimeFirst() {
        Recording p = new Recording();
        new EdgeBatchReplay(p).replay(List.of(event(3L, 3000L), event(1L, 1000L), event(2L, 2000L)), "EDGE_BATCH");
        assertEquals(List.of(1L, 2L, 3L), p.keys);
    }

    @Test
    void anEventWithoutATimeCountsAsTheOldest() {
        Recording p = new Recording();
        new EdgeBatchReplay(p).replay(List.of(event(2L, 2000L), event(9L, null)), "EDGE_BATCH");
        assertEquals(List.of(9L, 2L), p.keys);
    }

    @Test
    void theCallersListIsNotReordered() {
        List<SafetrustEventDto> given = new ArrayList<>(List.of(event(3L, 3000L), event(1L, 1000L)));
        new EdgeBatchReplay(new Recording()).replay(given, "EDGE_BATCH");
        assertEquals(3L, given.get(0).getEventKey());
        assertEquals(1L, given.get(1).getEventKey());
    }

    @Test
    void oneFailureDoesNotStopTheEventsAfterIt() {
        Recording p = new Recording(2L);
        EdgeBatchReplay.Outcome out = new EdgeBatchReplay(p).replay(
                List.of(event(1L, 1000L), event(2L, 2000L), event(3L, 3000L)), "EDGE_BATCH");
        assertEquals(List.of(1L, 2L, 3L), p.keys);
        assertEquals(2, out.processed());
        assertEquals(1, out.failed());
        assertFalse(out.allOk());
    }

    @Test
    void everyEventGetsItsOwnResultInReplayOrder() {
        EdgeBatchReplay.Outcome out = new EdgeBatchReplay(new Recording(2L)).replay(
                List.of(event(3L, 3000L), event(2L, 2000L), event(1L, 1000L)), "EDGE_BATCH");
        assertEquals(3, out.results().size());
        assertEquals(1L, out.results().get(0).eventKey());
        assertTrue(out.results().get(0).ok());
        assertNull(out.results().get(0).error());
        assertEquals(2L, out.results().get(1).eventKey());
        assertFalse(out.results().get(1).ok());
        assertEquals("database down for 2", out.results().get(1).error());
        assertTrue(out.results().get(2).ok());
    }

    @Test
    void theProcessorIsToldTheRouteAndATimestampForEveryEvent() {
        Recording p = new Recording();
        new EdgeBatchReplay(p).replay(List.of(event(1L, 1000L), event(2L, 2000L)), "EDGE_BATCH");
        assertEquals(List.of("EDGE_BATCH", "EDGE_BATCH"), p.modes);
        assertEquals(2, p.nanos.size());
    }

    @Test
    void aBatchWithNoFailureIsAllOk() {
        EdgeBatchReplay.Outcome out = new EdgeBatchReplay(new Recording()).replay(List.of(event(1L, 1000L)), "EDGE_BATCH");
        assertTrue(out.allOk());
        assertEquals(1, out.processed());
        assertEquals(0, out.failed());
    }

    @Test
    void replayingTheBatchAgainThroughAnIdempotentProcessorStoresEachEventOnce() {
        InMemoryTelemetryProcessor store = new InMemoryTelemetryProcessor();
        EdgeBatchReplay replay = new EdgeBatchReplay(store);
        List<SafetrustEventDto> batch = List.of(event(1L, 1000L), event(2L, 2000L));
        replay.replay(batch, "EDGE_BATCH");
        replay.replay(batch, "EDGE_BATCH");
        assertEquals(2, store.stored());
    }
}
