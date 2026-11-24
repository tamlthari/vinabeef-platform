package com.vinabeef.ingestion;

import com.vinabeef.ingestion.port.DeadLetterStore;
import com.vinabeef.ingestion.port.DeadLetterStore.OpenLetter;
import com.vinabeef.ingestion.port.EventPayloadParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Keeps events that could not be processed, so a database outage or a bug never turns into a lost event.
 *
 * <p>The MQTT subscriber has nobody to retry for it: once the callback returns, the broker considers the
 * message delivered. A failed event is therefore written here (its own transaction, so it survives the failed
 * one) and can be replayed later with {@link #replayOpen(int)}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeadLetterService {

    private final DeadLetterStore letters;
    private final EventPayloadParser parser;
    private final TelemetryEventProcessor eventProcessor;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(String ingressMode, Long eventKey, String error, String payload) {
        letters.add(ingressMode, eventKey, error, payload);
    }

    public int countOpen() {
        return letters.countOpen();
    }

    /**
     * Replays up to {@code limit} open dead letters, oldest event first (by hardware time, so inner/outer
     * order is kept). A letter is marked replayed once the event is processed or recognised as a duplicate.
     */
    public Map<String, Integer> replayOpen(int limit) {
        List<OpenLetter> rows = letters.findOpen(limit);

        record Parsed(long id, String payload, SafetrustEventDto dto) {
        }
        List<Parsed> parsed = new ArrayList<>();
        int unreadable = 0;
        for (OpenLetter row : rows) {
            try {
                SafetrustEventDto dto = parser.parse(row.payload());
                dto.setRawPayload(row.payload());
                parsed.add(new Parsed(row.id(), row.payload(), dto));
            } catch (Exception ex) {              // unparseable, or parsed to nothing: either way the letter stays open
                unreadable++;
                letters.markFailed(row.id(), "Unreadable payload: " + ex.getMessage());
            }
        }
        parsed.sort(Comparator.comparingLong(p -> p.dto().getTimestamp() != null ? p.dto().getTimestamp() : 0L));

        int replayed = 0;
        int failed = 0;
        for (Parsed p : parsed) {
            try {
                eventProcessor.processSafetrustRead(p.dto(), "DEAD_LETTER_REPLAY", System.nanoTime());
                letters.markReplayed(p.id());
                replayed++;
            } catch (Exception ex) {
                failed++;
                letters.markFailed(p.id(), ex.getMessage());
            }
        }
        log.info("[DEAD_LETTER] Replay finished: {} replayed, {} failed, {} unreadable.", replayed, failed, unreadable);
        return Map.of("replayed", replayed, "failed", failed + unreadable);
    }
}
