package com.vinabeef.movement.adapter;

import com.vinabeef.movement.GateCrossingResolver.Side;
import com.vinabeef.movement.GateCrossingResolver.Sighting;
import com.vinabeef.movement.port.GateCrossingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of the {@link GateCrossingStore} port.
 *
 * <p>All methods join the caller's transaction. They use {@link JdbcTemplate} because the rows they touch
 * point at {@code rfid_read_events} rows that were inserted with JDBC in the same transaction.
 */
@Component
@RequiredArgsConstructor
public class JdbcGateCrossingStore implements GateCrossingStore {

    private final JdbcTemplate jdbc;

    @Override
    public Optional<ReaderBinding> findReader(String readerName) {
        if (readerName == null) {
            return Optional.empty();
        }
        return jdbc.query(
                "SELECT reader_name, gate_id, side FROM gate_readers WHERE reader_name = ?",
                (rs, n) -> new ReaderBinding(rs.getString(1), rs.getString(2), Side.parse(rs.getString(3))),
                readerName).stream().findFirst();
    }

    @Override
    public void lockCrossing(String tagEpc, String gateId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {
        }, tagEpc + "|" + gateId);
    }

    @Override
    public List<Sighting> findUnpaired(String tagEpc, String gateId, long fromMs, long toMs) {
        return jdbc.query("""
                        SELECT side, seen_ms, event_id
                        FROM gate_crossing_candidates
                        WHERE tag_epc = ? AND gate_id = ? AND seen_ms BETWEEN ? AND ?
                        ORDER BY seen_ms, event_id
                        """,
                (rs, n) -> new Sighting(Side.parse(rs.getString(1)), rs.getLong(2), rs.getString(3)),
                tagEpc, gateId, fromMs, toMs);
    }

    @Override
    public void addUnpaired(String tagEpc, String gateId, Sighting sighting) {
        jdbc.update("""
                INSERT INTO gate_crossing_candidates (event_id, tag_epc, gate_id, side, seen_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, sighting.eventId(), tagEpc, gateId, sighting.side().name(), sighting.eventMs());
    }

    @Override
    public void consume(String tagEpc, String gateId, String partnerEventId, long laterMs, long windowMs) {
        jdbc.update("""
                DELETE FROM gate_crossing_candidates
                WHERE tag_epc = ? AND gate_id = ?
                  AND (event_id = ? OR seen_ms BETWEEN ? AND ?)
                """, tagEpc, gateId, partnerEventId, laterMs - windowMs, laterMs);
    }

    @Override
    public void pruneBefore(String tagEpc, String gateId, long beforeMs) {
        jdbc.update("DELETE FROM gate_crossing_candidates WHERE tag_epc = ? AND gate_id = ? AND seen_ms < ?",
                tagEpc, gateId, beforeMs);
    }

    @Override
    public boolean crossingCompletedNear(String animalId, String gateId, long eventMs, long debounceMs) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM movements
                WHERE animal_id = ? AND gate_id = ? AND detection_method = 'PAIRED_READERS'
                  AND occurred_at > ? AND occurred_at < ?
                """, Integer.class, animalId, gateId, ts(eventMs - debounceMs), ts(eventMs + debounceMs));
        return n != null && n > 0;
    }

    @Override
    public boolean movementNear(String animalId, String gateId, long eventMs, long debounceMs) {
        if (debounceMs <= 0) {
            return false;
        }
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM movements
                WHERE animal_id = ? AND gate_id = ?
                  AND occurred_at > ? AND occurred_at < ?
                """, Integer.class, animalId, gateId, ts(eventMs - debounceMs), ts(eventMs + debounceMs));
        return n != null && n > 0;
    }

    @Override
    public void touchReader(String readerName, long eventMs) {
        if (readerName == null) {
            return;
        }
        jdbc.update("""
                UPDATE gate_readers
                SET last_seen_at = GREATEST(COALESCE(last_seen_at, ?), ?)
                WHERE reader_name = ?
                """, ts(eventMs), ts(eventMs), readerName);
    }

    @Override
    public void setConnection(String readerName, String status, String ipAddress, long eventMs) {
        if (readerName == null) {
            return;
        }
        jdbc.update("""
                UPDATE gate_readers
                SET connection_status = ?,
                    last_ip = COALESCE(?, last_ip),
                    status_changed_at = ?
                WHERE reader_name = ?
                  AND (status_changed_at IS NULL OR status_changed_at <= ?)
                """, status, ipAddress, ts(eventMs), readerName, ts(eventMs));
    }

    private static Timestamp ts(long epochMs) {
        return Timestamp.from(Instant.ofEpochMilli(epochMs));
    }
}
