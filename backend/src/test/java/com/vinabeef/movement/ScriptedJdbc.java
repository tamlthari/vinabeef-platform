package com.vinabeef.movement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JdbcTemplate that runs nothing: it records which statement was asked for (by name) with its arguments and answers from
 * a script. Lets the order of statements and their arguments be tested without a database.
 */
class ScriptedJdbc extends JdbcTemplate {

    final List<String> calls = new ArrayList<>();
    final Map<String, Object[]> lastArgs = new LinkedHashMap<>();
    private final Deque<List<String>> listAnswers = new ArrayDeque<>();
    private final Deque<Integer> updateAnswers = new ArrayDeque<>();

    ScriptedJdbc answerList(String... values) {
        listAnswers.add(List.of(values));
        return this;
    }

    ScriptedJdbc answerUpdate(int rows) {
        updateAnswers.add(rows);
        return this;
    }

    private static String name(String sql) {
        if (sql.contains("pg_advisory_xact_lock")) return "LOCK";
        if (sql.contains("SELECT DISTINCT r.card_uuid")) return "HISTORY";
        if (sql.contains("FROM rfid_tags")) return "REGISTER";
        if (sql.contains("SELECT event_id FROM rfid_read_events") && sql.contains("hardware_timestamp BETWEEN")) return "FIND_TWIN";
        if (sql.contains("SET twin_event_id")) return "MARK_PRIMARY";
        if (sql.contains("SET duplicate_of")) return "MARK_DUPLICATE";
        if (sql.contains("FROM gate_readers WHERE reader_name")) return "FIND_READER";
        if (sql.contains("INSERT INTO rfid_read_events")) return "RAW_INSERT";
        if (sql.contains("SET last_seen_at")) return "TOUCH";
        if (sql.contains("Unregistered gate reader")) return "ALERT_UNREGISTERED";
        if (sql.contains("matches no known card")) return "ALERT_UNMAPPED";
        return "OTHER: " + sql.strip().split("\\s+", 4)[0];
    }

    private void record(String sql, Object[] args) {
        String n = name(sql);
        calls.add(n);
        lastArgs.put(n, args);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
        record(sql, args);
        List<String> next = listAnswers.poll();
        return (List<T>) (next == null ? List.of() : next);
    }

    @Override
    public int update(String sql, Object... args) {
        record(sql, args);
        Integer next = updateAnswers.poll();
        return next == null ? 1 : next;
    }

    @Override
    public void query(String sql, RowCallbackHandler rch, Object... args) {
        record(sql, args);
    }

    /** Every lookup that returns rows answers "no rows": no reader is registered, so a read ends at the reader check. */
    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        record(sql, args);
        return List.of();
    }
}
