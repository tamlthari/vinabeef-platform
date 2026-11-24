package com.vinabeef.movement.adapter;

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
    private final Deque<Integer> countAnswers = new ArrayDeque<>();
    private final Deque<List<Map<String, Object>>> rowAnswers = new ArrayDeque<>();

    ScriptedJdbc answerList(String... values) {
        listAnswers.add(List.of(values));
        return this;
    }

    /** The next COUNT(*) query answers {@code n}. */
    ScriptedJdbc answerCount(int n) {
        countAnswers.add(n);
        return this;
    }

    /** The next queryForList(sql, args) that returns rows answers these rows. */
    ScriptedJdbc answerRows(Map<String, Object> row) {
        rowAnswers.add(List.of(row));
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
        if (sql.contains("'UNEXPECTED_TAG', 'HIGH'")) return "ALERT_UNEXPECTED_TAG";
        if (sql.contains("'WRONG_BARN'")) return "ALERT_WRONG_BARN";
        if (sql.contains("MISSING_CATTLE")) return "RESOLVE_MISSING";
        if (sql.contains("daily_in_count - 1")) return "UNDO_DAILY_IN";
        if (sql.contains("daily_out_count - 1")) return "UNDO_DAILY_OUT";
        if (sql.contains("daily_in_count")) return "DAILY_IN";
        if (sql.contains("daily_out_count")) return "DAILY_OUT";
        if (sql.contains("SELECT assigned_barn_id")) return "ASSIGNED_BARN";
        if (sql.contains("FROM animals WHERE animal_id")) return "LIVE_ANIMAL";
        if (sql.contains("ORDER BY hardware_timestamp, event_id")) return "UNMAPPED_IDS";
        if (sql.contains("SET card_uuid = ?")) return "RESOLVE_ROW";
        if (sql.contains("FROM movements") && sql.contains("crossing_status")) return "NEIGHBOURS";
        if (sql.contains("FROM movements")) return "MOVEMENT_NEAR";
        if (sql.contains("event_class = 'LOCAL_UNMAPPED'") && sql.contains("COUNT")) return "COUNT_UNMAPPED";
        if (sql.contains("FOR UPDATE") && sql.contains("LOCAL_UNMAPPED")) return "UNMAPPED_ROW";
        if (sql.contains("SET connection_status")) return "SET_CONNECTION";
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
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        record(sql, args);
        List<Map<String, Object>> next = rowAnswers.poll();
        return next == null ? List.of() : next;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        record(sql, args);
        Integer next = countAnswers.poll();
        return (T) (next == null ? Integer.valueOf(0) : next);
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
