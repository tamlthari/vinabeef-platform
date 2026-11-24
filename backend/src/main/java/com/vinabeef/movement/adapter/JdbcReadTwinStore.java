package com.vinabeef.movement.adapter;

import com.vinabeef.movement.port.ReadTwinStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@link ReadTwinStore} over {@code rfid_read_events}. */
@Component
@RequiredArgsConstructor
public class JdbcReadTwinStore implements ReadTwinStore {

    private final JdbcTemplate jdbc;

    @Override
    public void lock(String card, String readerName) {
        LocalReadSql.Query lock = LocalReadSql.lock(card, readerName);
        jdbc.query(lock.sql(), rs -> {
        }, lock.params().toArray());
    }

    @Override
    public List<String> findTwin(String eventId, String readerName, String card, String readSource, long hardwareMs, long windowMs) {
        LocalReadSql.Query find = LocalReadSql.findTwin(eventId, readerName, card, readSource, hardwareMs, windowMs);
        return jdbc.queryForList(find.sql(), String.class, find.params().toArray());
    }

    @Override
    public boolean markPrimary(String first, String eventId) {
        LocalReadSql.Query primary = LocalReadSql.markPrimary(first, eventId);
        return jdbc.update(primary.sql(), primary.params().toArray()) == 1;
    }

    @Override
    public void markDuplicate(String eventId, String first) {
        LocalReadSql.Query copy = LocalReadSql.markDuplicate(eventId, first);
        jdbc.update(copy.sql(), copy.params().toArray());
    }
}
