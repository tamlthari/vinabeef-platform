package com.vinabeef.ingestion.adapter;

import com.vinabeef.ingestion.port.DeadLetterStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** {@link DeadLetterStore} over {@code ingestion_dead_letters}. */
@Component
@RequiredArgsConstructor
public class JdbcDeadLetterStore implements DeadLetterStore {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void add(String ingressMode, Long eventKey, String error, String payload) {
        jdbcTemplate.update("""
                INSERT INTO ingestion_dead_letters (ingress_mode, event_key, error_message, payload)
                VALUES (?, ?, ?, ?)
                """, ingressMode, eventKey, error != null ? error : "unknown error", payload != null ? payload : "");
    }

    @Override
    public int countOpen() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ingestion_dead_letters WHERE replayed_at IS NULL", Integer.class);
        return n != null ? n : 0;
    }

    @Override
    public List<OpenLetter> findOpen(int limit) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT dead_letter_id, payload FROM ingestion_dead_letters
                WHERE replayed_at IS NULL ORDER BY dead_letter_id LIMIT ?
                """, limit);
        return rows.stream()
                .map(row -> new OpenLetter(((Number) row.get("dead_letter_id")).longValue(), String.valueOf(row.get("payload"))))
                .toList();
    }

    @Override
    public void markReplayed(long id) {
        jdbcTemplate.update("UPDATE ingestion_dead_letters SET replayed_at = CURRENT_TIMESTAMP WHERE dead_letter_id = ?", id);
    }

    @Override
    public void markFailed(long id, String error) {
        jdbcTemplate.update("UPDATE ingestion_dead_letters SET error_message = ? WHERE dead_letter_id = ?",
                error != null ? error : "unknown error", id);
    }
}
