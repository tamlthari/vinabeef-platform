package com.vinabeef.movement.adapter;

import com.vinabeef.movement.GateCrossingResolver.Known;
import com.vinabeef.movement.GateCrossingResolver.Side;
import com.vinabeef.movement.port.GateCrossingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
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
    public Neighbours neighbours(String animalId, String gateId, long eventMs) {
        Timestamp at = ts(eventMs);
        List<Object[]> rows = jdbc.query("""
                        (SELECT 'P', movement_id, crossing_status, reader_side, crossing_started_at, occurred_at
                           FROM movements
                          WHERE animal_id = ? AND gate_id = ? AND occurred_at <= ?
                          ORDER BY occurred_at DESC, movement_id DESC
                          LIMIT 1)
                        UNION ALL
                        (SELECT 'N', movement_id, crossing_status, reader_side, crossing_started_at, occurred_at
                           FROM movements
                          WHERE animal_id = ? AND gate_id = ? AND occurred_at > ?
                          ORDER BY occurred_at ASC, movement_id ASC
                          LIMIT 1)
                        """,
                (rs, n) -> new Object[]{rs.getString(1), known(rs)},
                animalId, gateId, at, animalId, gateId, at);
        Known previous = null;
        Known next = null;
        for (Object[] row : rows) {
            if ("P".equals(row[0])) {
                previous = (Known) row[1];
            } else {
                next = (Known) row[1];
            }
        }
        return new Neighbours(previous, next);
    }

    private static Known known(ResultSet rs) throws SQLException {
        long at = rs.getTimestamp(6).getTime();
        Timestamp started = rs.getTimestamp(5);
        return new Known(rs.getString(2), "HALF_CROSSING".equals(rs.getString(3)), Side.parse(rs.getString(4)),
                started != null ? started.getTime() : at, at);
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
