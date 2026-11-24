package com.vinabeef.movement.adapter;

import com.vinabeef.facilities.BarnRepository;
import com.vinabeef.movement.port.BarnCounters;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@link BarnCounters}: the headcount through the barn repository, the daily counters through JDBC. */
@Component
@RequiredArgsConstructor
public class JdbcBarnCounters implements BarnCounters {

    private final BarnRepository barns;
    private final JdbcTemplate jdbc;

    @Override
    public void adjustHeadcount(String barnId, int delta) {
        barns.adjustHeadcount(barnId, delta);
    }

    @Override
    public void countIn(String barnId) {
        jdbc.update("UPDATE barns SET daily_in_count = daily_in_count + 1 WHERE barn_id = ?", barnId);
    }

    @Override
    public void countOut(String barnId) {
        jdbc.update("UPDATE barns SET daily_out_count = daily_out_count + 1 WHERE barn_id = ?", barnId);
    }
}
