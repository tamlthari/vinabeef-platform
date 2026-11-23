package com.vinabeef.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Gives the demo supervisor accounts their barns the first time the application starts with an empty
 * {@code user_barn_assignments} table. It is demo data, so it lives apart from the access-control service.
 */
@Component
@RequiredArgsConstructor
public class DemoSupervisorAssignments {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureDemoSupervisorBarnAssignments() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_barn_assignments", Integer.class);
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.update("""
                INSERT INTO user_barn_assignments (username, barn_id)
                SELECT 'supervisor_daklak', barn_id FROM barns
                WHERE farm_id = 'FARM-DAK-LAK' ORDER BY current_headcount DESC, code ASC LIMIT 3
                ON CONFLICT DO NOTHING
            """);
            jdbcTemplate.update("""
                INSERT INTO user_barn_assignments (username, barn_id)
                SELECT 'supervisor_barn1', barn_id FROM barns
                WHERE farm_id = 'FARM-DAK-LAK' ORDER BY code ASC LIMIT 2
                ON CONFLICT DO NOTHING
            """);
            jdbcTemplate.update("""
                INSERT INTO user_barn_assignments (username, barn_id)
                SELECT 'supervisor_dongnai', barn_id FROM barns
                WHERE farm_id = 'FARM-DONG-NAI' ORDER BY current_headcount DESC, code ASC LIMIT 3
                ON CONFLICT DO NOTHING
            """);
            jdbcTemplate.update("""
                INSERT INTO user_barn_assignments (username, barn_id)
                SELECT 'supervisor_gialai', barn_id FROM barns
                WHERE farm_id = 'FARM-GIA-LAI' ORDER BY current_headcount DESC, code ASC LIMIT 3
                ON CONFLICT DO NOTHING
            """);
            jdbcTemplate.update("""
                INSERT INTO user_barn_assignments (username, barn_id)
                SELECT 'supervisor_gialai_2barns', barn_id FROM barns
                WHERE farm_id = 'FARM-GIA-LAI' ORDER BY code ASC LIMIT 2
                ON CONFLICT DO NOTHING
            """);
        } catch (Exception ignored) {
        }
    }
}
