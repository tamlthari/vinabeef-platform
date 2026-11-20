package com.vinabeef.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

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

    private final JdbcTemplate jdbcTemplate;
    private final TelemetryEventProcessor eventProcessor;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(String ingressMode, Long eventKey, String error, String payload) {
        jdbcTemplate.update("""
                INSERT INTO ingestion_dead_letters (ingress_mode, event_key, error_message, payload)
                VALUES (?, ?, ?, ?)
                """, ingressMode, eventKey, error != null ? error : "unknown error", payload != null ? payload : "");
    }

    public int countOpen() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ingestion_dead_letters WHERE replayed_at IS NULL", Integer.class);
        return n != null ? n : 0;
    }

    /**
     * Replays up to {@code limit} open dead letters, oldest event first (by hardware time, so inner/outer
     * order is kept). A letter is marked replayed once the event is processed or recognised as a duplicate.
     */
    public Map<String, Integer> replayOpen(int limit) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT dead_letter_id, payload FROM ingestion_dead_letters
                WHERE replayed_at IS NULL ORDER BY dead_letter_id LIMIT ?
                """, limit);

        record Parsed(long id, String payload, SafetrustEventDto dto) {
        }
        List<Parsed> parsed = new ArrayList<>();
        int unreadable = 0;
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("dead_letter_id")).longValue();
            String payload = String.valueOf(row.get("payload"));
            try {
                SafetrustEventDto dto = objectMapper.readValue(payload, SafetrustEventDto.class);
                dto.setRawPayload(payload);
                parsed.add(new Parsed(id, payload, dto));
            } catch (Exception ex) {
                unreadable++;
                markFailed(id, "Unreadable payload: " + ex.getMessage());
            }
        }
        parsed.sort(Comparator.comparingLong(p -> p.dto().getTimestamp() != null ? p.dto().getTimestamp() : 0L));

        int replayed = 0;
        int failed = 0;
        for (Parsed p : parsed) {
            try {
                eventProcessor.processSafetrustRead(p.dto(), "DEAD_LETTER_REPLAY", System.nanoTime());
                jdbcTemplate.update("UPDATE ingestion_dead_letters SET replayed_at = CURRENT_TIMESTAMP WHERE dead_letter_id = ?", p.id());
                replayed++;
            } catch (Exception ex) {
                failed++;
                markFailed(p.id(), ex.getMessage());
            }
        }
        log.info("[DEAD_LETTER] Replay finished: {} replayed, {} failed, {} unreadable.", replayed, failed, unreadable);
        return Map.of("replayed", replayed, "failed", failed + unreadable);
    }

    private void markFailed(long id, String error) {
        jdbcTemplate.update("UPDATE ingestion_dead_letters SET error_message = ? WHERE dead_letter_id = ?",
                error != null ? error : "unknown error", id);
    }
}
