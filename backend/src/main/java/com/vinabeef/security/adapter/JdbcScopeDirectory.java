package com.vinabeef.security.adapter;

import com.vinabeef.security.port.ScopeDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@link ScopeDirectory} over the users, user_barn_assignments and barns tables. */
@Component
@RequiredArgsConstructor
public class JdbcScopeDirectory implements ScopeDirectory {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<Account> findAccount(String username) {
        var rows = jdbcTemplate.queryForList(
                "SELECT username, role, farm_id FROM users WHERE username = ?", username);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String role = String.valueOf(rows.get(0).get("role"));
        Object rawFarm = rows.get(0).get("farm_id");
        String farmId = rawFarm != null ? String.valueOf(rawFarm) : null;
        return Optional.of(new Account(username, role, farmId));
    }

    @Override
    public List<String> barnsAssignedTo(String username) {
        return jdbcTemplate.queryForList(
                "SELECT barn_id FROM user_barn_assignments WHERE username = ? ORDER BY barn_id",
                String.class, username);
    }

    @Override
    public Optional<String> farmOfBarn(String barnId) {
        var farms = jdbcTemplate.queryForList(
                "SELECT farm_id FROM barns WHERE barn_id = ?", String.class, barnId);
        return farms.isEmpty() ? Optional.empty() : Optional.ofNullable(farms.get(0));
    }
}
