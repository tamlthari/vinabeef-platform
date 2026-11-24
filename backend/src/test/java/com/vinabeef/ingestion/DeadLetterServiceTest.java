package com.vinabeef.ingestion;

import com.vinabeef.ingestion.port.DeadLetterStore;
import com.vinabeef.ingestion.port.EventPayloadParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeping failed events and replaying them: the rules, with the store, the parser and the processor standing in. */
class DeadLetterServiceTest {

    /** The store, in memory. Open letters are listed oldest first, as the database does. */
    private static final class Letters implements DeadLetterStore {
        final List<OpenLetter> open = new ArrayList<>();
        final List<List<Object>> added = new ArrayList<>();
        final Set<Long> replayed = new LinkedHashSet<>();
        final Map<Long, String> failed = new LinkedHashMap<>();
        int askedLimit;

        @Override
        public void add(String ingressMode, Long eventKey, String error, String payload) {
            added.add(java.util.Arrays.asList(ingressMode, eventKey, error, payload));
        }

        @Override
        public int countOpen() {
            return (int) open.stream().filter(l -> !replayed.contains(l.id())).count();
        }

        @Override
        public List<OpenLetter> findOpen(int limit) {
            askedLimit = limit;
            return open.stream().limit(limit).toList();
        }

        @Override
        public void markReplayed(long id) {
            replayed.add(id);
        }

        @Override
        public void markFailed(long id, String error) {
            failed.put(id, error);
        }
    }

    /** Payloads look like {@code key=5;ts=300}; anything starting with BAD cannot be read. */
    private static final EventPayloadParser PARSER = payload -> {
        if (payload.startsWith("BAD")) {
            throw new EventPayloadParser.UnreadablePayloadException("not json", null);
        }
        SafetrustEventDto dto = new SafetrustEventDto();
        for (String part : payload.split(";")) {
            String[] kv = part.split("=");
            if (kv[0].equals("key")) dto.setEventKey(Long.parseLong(kv[1]));
            if (kv[0].equals("ts")) dto.setTimestamp(Long.parseLong(kv[1]));
        }
        return dto;
    };

    private static final class Processor implements TelemetryEventProcessor {
        final List<Long> keys = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();
        final List<String> modes = new ArrayList<>();
        final Set<Long> refuse = new LinkedHashSet<>();

        @Override
        public void processSafetrustRead(SafetrustEventDto dto, String ingressMode, long tIngressNano) {
            keys.add(dto.getEventKey());
            payloads.add(dto.getRawPayload());
            modes.add(ingressMode);
            if (refuse.contains(dto.getEventKey())) {
                throw new IllegalStateException("database down");
            }
        }

        @Override
        public boolean reprocessUnmappedLocalRead(String eventId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> unmappedLocalReadIds(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countUnmappedLocalReads() {
            throw new UnsupportedOperationException();
        }
    }

    private final Letters letters = new Letters();
    private final Processor processor = new Processor();

    private DeadLetterService service() {
        return new DeadLetterService(letters, PARSER, processor);
    }

    @Test
    void aFailedEventIsKeptWithItsRouteKeyErrorAndPayload() {
        service().store("DIRECT_MQTT", 7L, "db down", "{...}");
        assertEquals(List.of(java.util.Arrays.<Object>asList("DIRECT_MQTT", 7L, "db down", "{...}")), letters.added);
    }

    @Test
    void theOpenCountIsWhatTheStoreSays() {
        letters.open.add(new DeadLetterStore.OpenLetter(1, "key=1;ts=10"));
        assertEquals(1, service().countOpen());
    }

    @Test
    void lettersAreReplayedOldestEventFirstWhateverTheirOrderInTheStore() {
        letters.open.add(new DeadLetterStore.OpenLetter(1, "key=11;ts=300"));
        letters.open.add(new DeadLetterStore.OpenLetter(2, "key=12;ts=100"));
        letters.open.add(new DeadLetterStore.OpenLetter(3, "key=13;ts=200"));

        Map<String, Integer> result = service().replayOpen(100);

        assertEquals(List.of(12L, 13L, 11L), processor.keys, "by event time, so inner and outer reads keep their order");
        assertEquals(Map.of("replayed", 3, "failed", 0), result);
        assertEquals(Set.of(1L, 2L, 3L), letters.replayed);
        assertEquals(List.of("DEAD_LETTER_REPLAY", "DEAD_LETTER_REPLAY", "DEAD_LETTER_REPLAY"), processor.modes);
    }

    @Test
    void theProcessorGetsTheStoredPayloadAsTheRawPayload() {
        letters.open.add(new DeadLetterStore.OpenLetter(1, "key=11;ts=300"));
        service().replayOpen(10);
        assertEquals(List.of("key=11;ts=300"), processor.payloads);
    }

    @Test
    void aPayloadThatCannotBeReadIsMarkedAndTheOthersStillReplay() {
        letters.open.add(new DeadLetterStore.OpenLetter(1, "BAD payload"));
        letters.open.add(new DeadLetterStore.OpenLetter(2, "key=12;ts=100"));

        Map<String, Integer> result = service().replayOpen(10);

        assertEquals(Map.of("replayed", 1, "failed", 1), result);
        assertEquals(Map.of(1L, "Unreadable payload: not json"), letters.failed);
        assertEquals(Set.of(2L), letters.replayed);
    }

    @Test
    void aPayloadThatParsesToNothingIsUnreadableToo() {
        DeadLetterService svc = new DeadLetterService(letters, p -> null, processor);
        letters.open.add(new DeadLetterStore.OpenLetter(1, "null"));
        assertEquals(Map.of("replayed", 0, "failed", 1), svc.replayOpen(10));
        assertTrue(letters.failed.get(1L).startsWith("Unreadable payload"));
    }

    @Test
    void anEventTheProcessorRefusesStaysOpenWithItsErrorAndTheNextOneIsTried() {
        processor.refuse.add(12L);
        letters.open.add(new DeadLetterStore.OpenLetter(1, "key=12;ts=100"));
        letters.open.add(new DeadLetterStore.OpenLetter(2, "key=13;ts=200"));

        Map<String, Integer> result = service().replayOpen(10);

        assertEquals(Map.of("replayed", 1, "failed", 1), result);
        assertEquals(Map.of(1L, "database down"), letters.failed);
        assertEquals(Set.of(2L), letters.replayed);
    }

    @Test
    void nothingOpenReplaysNothing() {
        assertEquals(Map.of("replayed", 0, "failed", 0), service().replayOpen(10));
        assertTrue(processor.keys.isEmpty());
    }

    @Test
    void theLimitIsPassedToTheStore() {
        service().replayOpen(25);
        assertEquals(25, letters.askedLimit);
    }
}
