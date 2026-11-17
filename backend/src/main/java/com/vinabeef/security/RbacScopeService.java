package com.vinabeef.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class RbacScopeService {

    private final JdbcTemplate jdbcTemplate;

    public record UserScope(
            String username,
            String role,
            String farmId,
            List<String> barnIds
    ) {
        public boolean isGlobal() {
            return "SYSTEM_ADMIN".equals(role) || "EXECUTIVE".equals(role);
        }

        public boolean isFarmScoped() {
            return "FARM_MANAGER".equals(role);
        }

        public boolean isBarnScoped() {
            return "BARN_SUPERVISOR".equals(role);
        }

        public boolean canAccessFarm(String targetFarmId) {
            if (isGlobal()) return true;
            if (targetFarmId == null) return false;
            return targetFarmId.equals(farmId);
        }

        public boolean canAccessBarn(String targetBarnId, String targetFarmId) {
            if (isGlobal()) return true;
            if (isFarmScoped()) {
                return farmId != null && farmId.equals(targetFarmId);
            }
            if (isBarnScoped()) {
                return targetBarnId != null && barnIds.contains(targetBarnId);
            }
            return false;
        }
    }

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

    public UserScope getCurrentScope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return new UserScope("anonymous", "NONE", null, List.of());
        }

        String username = auth.getName();
        var rows = jdbcTemplate.queryForList(
                "SELECT username, role, farm_id FROM users WHERE username = ?", username);

        if (rows.isEmpty()) {
            return new UserScope(username, "NONE", null, List.of());
        }

        String role = String.valueOf(rows.get(0).get("role"));
        Object rawFarm = rows.get(0).get("farm_id");
        String farmId = rawFarm != null ? String.valueOf(rawFarm) : null;

        List<String> barnIds = List.of();
        if ("BARN_SUPERVISOR".equals(role)) {
            barnIds = jdbcTemplate.queryForList(
                    "SELECT barn_id FROM user_barn_assignments WHERE username = ? ORDER BY barn_id",
                    String.class, username);
        }

        return new UserScope(username, role, farmId, barnIds);
    }

    public boolean canAccessBarnId(UserScope scope, String barnId) {
        if (scope.isGlobal()) return true;
        if (barnId == null || barnId.isBlank()) return false;
        if (scope.isBarnScoped()) {
            return scope.barnIds().contains(barnId);
        }
        if (scope.isFarmScoped()) {
            var farms = jdbcTemplate.queryForList(
                    "SELECT farm_id FROM barns WHERE barn_id = ?", String.class, barnId);
            return !farms.isEmpty() && farms.get(0).equals(scope.farmId());
        }
        return false;
    }
}