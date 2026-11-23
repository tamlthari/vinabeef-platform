package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.security.BarnAccessPolicy;
import com.vinabeef.security.CurrentScopeProvider;
import com.vinabeef.security.UserScope;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@SuppressWarnings("unchecked")
public class UserManagementController {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final CurrentScopeProvider currentScope;
    private final BarnAccessPolicy barnAccess;

    private static final Set VALID_ROLES = Set.of(
            "SYSTEM_ADMIN", "FARM_MANAGER", "BARN_SUPERVISOR", "EXECUTIVE"
    );

    @Data
    public static class CreateUserRequest {
        private String username;
        private String fullName;
        private String password;
        private String role;
        private String farmId;
        private List barnIds;
    }

    @Data
    public static class UpdateUserRoleRequest {
        private String role;
        private String farmId;
        private List barnIds;
    }

    @Data
    public static class UpdateUserRequest {
        private String fullName;
        private String role;
        private String farmId;
        private List barnIds;
        /** Optional. Blank or missing keeps the current password. */
        private String newPassword;
        /** Optional. Missing keeps the current state. */
        private Boolean enabled;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER')")
    public ResponseEntity listUsers() {
        var scope = currentScope.getCurrentScope();

        List users;
        if (scope.isGlobal()) {
            users = jdbcTemplate.queryForList("""
                SELECT u.user_id, u.username, COALESCE(u.full_name, u.username) AS full_name,
                       u.role, COALESCE(u.farm_id, 'ALL') AS farm_id, f.name AS farm_name,
                       u.enabled, u.created_at
                FROM users u
                LEFT JOIN farms f ON u.farm_id = f.farm_id
                ORDER BY u.role ASC, u.username ASC
            """);
        } else if (scope.isFarmScoped() && scope.farmId() != null) {
            users = jdbcTemplate.queryForList("""
                SELECT u.user_id, u.username, COALESCE(u.full_name, u.username) AS full_name,
                       u.role, COALESCE(u.farm_id, 'ALL') AS farm_id, f.name AS farm_name,
                       u.enabled, u.created_at
                FROM users u
                LEFT JOIN farms f ON u.farm_id = f.farm_id
                WHERE u.farm_id = ?
                ORDER BY u.role ASC, u.username ASC
            """, scope.farmId());
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Insufficient privileges"));
        }

        var allAssignments = jdbcTemplate.queryForList("""
            SELECT uba.username, uba.barn_id, b.code AS barn_code
            FROM user_barn_assignments uba
            LEFT JOIN barns b ON uba.barn_id = b.barn_id
            ORDER BY b.code ASC
        """);

        Map barnsByUser = new HashMap();
        for (Object obj : allAssignments) {
            Map row = (Map) obj;
            String uname = String.valueOf(row.get("username"));
            List userBarns = (List) barnsByUser.computeIfAbsent(uname, k -> new ArrayList());
            userBarns.add(Map.of(
                    "barnId", String.valueOf(row.get("barn_id")),
                    "code", String.valueOf(row.get("barn_code") != null ? row.get("barn_code") : row.get("barn_id"))
            ));
        }

        List enriched = new ArrayList();
        for (Object obj : users) {
            Map u = (Map) obj;
            Map copy = new LinkedHashMap(u);
            String uname = String.valueOf(u.get("username"));
            copy.put("assignedBarns", barnsByUser.getOrDefault(uname, List.of()));
            enriched.add(copy);
        }

        return ResponseEntity.ok(enriched);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER')")
    @Audited(action = "CREATE_USER", targetType = "USER")
    @Transactional
    public ResponseEntity createUser(@RequestBody CreateUserRequest req) {
        var scope = currentScope.getCurrentScope();

        if (req.getUsername() == null || req.getUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        }
        if (req.getPassword() == null || req.getPassword().length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 6 characters"));
        }

        String username = req.getUsername().trim().toLowerCase();
        String targetRole = (req.getRole() != null ? req.getRole().trim().toUpperCase() : "BARN_SUPERVISOR");
        if (!VALID_ROLES.contains(targetRole)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid role: " + targetRole));
        }

        String effectiveFarmId;
        if (scope.isFarmScoped()) {
            if ("SYSTEM_ADMIN".equals(targetRole) || "EXECUTIVE".equals(targetRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Farm Managers cannot create SYSTEM_ADMIN or EXECUTIVE accounts."));
            }
            effectiveFarmId = scope.farmId();
        } else {
            effectiveFarmId = ("SYSTEM_ADMIN".equals(targetRole) || "EXECUTIVE".equals(targetRole))
                    ? null
                    : (req.getFarmId() != null && !req.getFarmId().isBlank() && !"ALL".equalsIgnoreCase(req.getFarmId())
                            ? req.getFarmId().trim() : null);
        }

        if (("FARM_MANAGER".equals(targetRole) || "BARN_SUPERVISOR".equals(targetRole)) && effectiveFarmId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "A target farm must be selected for " + targetRole));
        }

        List cleanBarnIds;
        try {
            cleanBarnIds = sanitizeAndValidateBarns(scope, targetRole, effectiveFarmId, req.getBarnIds());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if ("BARN_SUPERVISOR".equals(targetRole) && cleanBarnIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one barn must be assigned to a Barn Supervisor."));
        }

        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        if (existing != null && existing > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username '" + username + "' already exists."));
        }

        String userId = "USR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String fullName = (req.getFullName() != null && !req.getFullName().isBlank())
                ? req.getFullName().trim() : username;
        String encodedPassword = passwordEncoder.encode(req.getPassword());

        jdbcTemplate.update("""
            INSERT INTO users (user_id, username, password_hash, role, farm_id, full_name)
            VALUES (?, ?, ?, ?, ?, ?)
        """, userId, username, encodedPassword, targetRole, effectiveFarmId, fullName);
        syncFarmScope(userId, effectiveFarmId);

        for (Object barnObj : cleanBarnIds) {
            jdbcTemplate.update("""
                INSERT INTO user_barn_assignments (username, barn_id)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
            """, username, String.valueOf(barnObj));
        }

        return ResponseEntity.ok(Map.of(
                "status", "CREATED",
                "userId", userId,
                "username", username,
                "role", targetRole,
                "farmId", effectiveFarmId != null ? effectiveFarmId : "ALL",
                "assignedBarns", cleanBarnIds
        ));
    }

    @PutMapping("/{targetUsername}/role-scope")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER')")
    @Audited(action = "UPDATE_USER_ROLE_SCOPE", targetType = "USER")
    @Transactional
    public ResponseEntity updateUserRoleAndScope(@PathVariable String targetUsername,
                                                 @RequestBody UpdateUserRoleRequest req) {
        var scope = currentScope.getCurrentScope();
        String cleanTarget = targetUsername.trim();

        var existingRows = jdbcTemplate.queryForList(
                "SELECT username, role, farm_id FROM users WHERE username = ?", cleanTarget);
        if (existingRows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String currentTargetFarm = existingRows.get(0).get("farm_id") != null
                ? String.valueOf(existingRows.get(0).get("farm_id")) : null;

        if (scope.isFarmScoped() && !Objects.equals(scope.farmId(), currentTargetFarm)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You can only modify users assigned to your own farm (" + scope.farmId() + ")."));
        }

        String newRole = (req.getRole() != null && !req.getRole().isBlank())
                ? req.getRole().trim().toUpperCase()
                : String.valueOf(existingRows.get(0).get("role"));

        if (!VALID_ROLES.contains(newRole)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid role: " + newRole));
        }

        if (scope.username().equalsIgnoreCase(cleanTarget) && !scope.role().equals(newRole)) {
            return ResponseEntity.badRequest().body(Map.of("error", "You cannot demote or change your own active role."));
        }

        String effectiveFarmId;
        if (scope.isFarmScoped()) {
            if ("SYSTEM_ADMIN".equals(newRole) || "EXECUTIVE".equals(newRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Farm Managers cannot grant SYSTEM_ADMIN or EXECUTIVE roles."));
            }
            effectiveFarmId = scope.farmId();
        } else {
            effectiveFarmId = ("SYSTEM_ADMIN".equals(newRole) || "EXECUTIVE".equals(newRole))
                    ? null
                    : (req.getFarmId() != null && !req.getFarmId().isBlank() && !"ALL".equalsIgnoreCase(req.getFarmId())
                            ? req.getFarmId().trim() : currentTargetFarm);
        }

        List cleanBarnIds;
        try {
            cleanBarnIds = sanitizeAndValidateBarns(scope, newRole, effectiveFarmId, req.getBarnIds());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if ("BARN_SUPERVISOR".equals(newRole) && cleanBarnIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "A Barn Supervisor must have at least one assigned barn."));
        }

        jdbcTemplate.update("UPDATE users SET role = ?, farm_id = ? WHERE username = ?",
                newRole, effectiveFarmId, cleanTarget);
        syncFarmScope(String.valueOf(jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE username = ?", String.class, cleanTarget)), effectiveFarmId);

        jdbcTemplate.update("DELETE FROM user_barn_assignments WHERE username = ?", cleanTarget);
        if ("BARN_SUPERVISOR".equals(newRole)) {
            for (Object barnObj : cleanBarnIds) {
                jdbcTemplate.update("""
                    INSERT INTO user_barn_assignments (username, barn_id)
                    VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                """, cleanTarget, String.valueOf(barnObj));
            }
        }

        return ResponseEntity.ok(Map.of(
                "status", "UPDATED",
                "username", cleanTarget,
                "role", newRole,
                "farmId", effectiveFarmId != null ? effectiveFarmId : "ALL",
                "assignedBarns", cleanBarnIds
        ));
    }

    /**
     * Full edit of one account: name, role, farm, barns, enabled flag and (optionally) a new password.
     * The older {@code /role-scope} endpoint stays for scripts that use it.
     */
    @PutMapping("/{targetUsername}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER')")
    @Audited(action = "UPDATE_USER", targetType = "USER")
    @Transactional
    public ResponseEntity updateUser(@PathVariable String targetUsername, @RequestBody UpdateUserRequest req) {
        var scope = currentScope.getCurrentScope();
        String cleanTarget = targetUsername.trim();

        var rows = jdbcTemplate.queryForList(
                "SELECT user_id, username, role, farm_id, enabled, full_name FROM users WHERE username = ?", cleanTarget);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User '" + cleanTarget + "' does not exist."));
        }
        Map row = (Map) rows.get(0);
        String userId = String.valueOf(row.get("user_id"));
        String currentRole = String.valueOf(row.get("role"));
        String currentFarm = row.get("farm_id") != null ? String.valueOf(row.get("farm_id")) : null;
        boolean currentEnabled = !Boolean.FALSE.equals(row.get("enabled"));

        String newRole = req.getRole() != null && !req.getRole().isBlank() ? req.getRole().trim().toUpperCase() : currentRole;
        if (!VALID_ROLES.contains(newRole)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid role: " + newRole));
        }
        boolean newEnabled = req.getEnabled() != null ? req.getEnabled() : currentEnabled;

        var denial = UserChangeGuard.denyChange(
                new UserChangeGuard.Actor(scope.username(), scope.role(), scope.farmId()),
                new UserChangeGuard.Target(cleanTarget, currentRole, currentFarm, currentEnabled),
                newRole, newEnabled, enabledAdminCount());
        if (denial != null) {
            return ResponseEntity.status(denial.status()).body(Map.of("error", denial.message()));
        }

        String effectiveFarmId;
        if (scope.isFarmScoped()) {
            effectiveFarmId = scope.farmId();
        } else if ("SYSTEM_ADMIN".equals(newRole) || "EXECUTIVE".equals(newRole)) {
            effectiveFarmId = null;
        } else if (req.getFarmId() != null && !req.getFarmId().isBlank() && !"ALL".equalsIgnoreCase(req.getFarmId())) {
            effectiveFarmId = req.getFarmId().trim();
        } else {
            effectiveFarmId = currentFarm;
        }
        if (("FARM_MANAGER".equals(newRole) || "BARN_SUPERVISOR".equals(newRole)) && effectiveFarmId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "A target farm must be selected for " + newRole));
        }
        if (effectiveFarmId != null && jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM farms WHERE farm_id = ?", Integer.class, effectiveFarmId) == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Farm does not exist: " + effectiveFarmId));
        }

        List cleanBarnIds;
        try {
            cleanBarnIds = sanitizeAndValidateBarns(scope, newRole, effectiveFarmId, req.getBarnIds());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if ("BARN_SUPERVISOR".equals(newRole) && cleanBarnIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "A Barn Supervisor must have at least one assigned barn."));
        }

        String newName = req.getFullName() != null && !req.getFullName().isBlank()
                ? req.getFullName().trim() : String.valueOf(row.get("full_name"));
        if (newName.length() > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "Full name is too long (100 characters maximum)."));
        }

        boolean passwordChanged = req.getNewPassword() != null && !req.getNewPassword().isBlank();
        if (passwordChanged && req.getNewPassword().length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 6 characters"));
        }

        jdbcTemplate.update("UPDATE users SET full_name = ?, role = ?, farm_id = ?, enabled = ? WHERE username = ?",
                newName, newRole, effectiveFarmId, newEnabled, cleanTarget);
        if (passwordChanged) {
            jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE username = ?",
                    passwordEncoder.encode(req.getNewPassword()), cleanTarget);
        }

        jdbcTemplate.update("DELETE FROM user_barn_assignments WHERE username = ?", cleanTarget);
        if ("BARN_SUPERVISOR".equals(newRole)) {
            for (Object barnObj : cleanBarnIds) {
                jdbcTemplate.update("INSERT INTO user_barn_assignments (username, barn_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        cleanTarget, String.valueOf(barnObj));
            }
        }
        syncFarmScope(userId, effectiveFarmId);

        Map result = new LinkedHashMap();
        result.put("status", "UPDATED");
        result.put("username", cleanTarget);
        result.put("role", newRole);
        result.put("farmId", effectiveFarmId != null ? effectiveFarmId : "ALL");
        result.put("enabled", newEnabled);
        result.put("assignedBarns", cleanBarnIds);
        result.put("passwordChanged", passwordChanged);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{targetUsername}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER')")
    @Audited(action = "DELETE_USER", targetType = "USER")
    @Transactional
    public ResponseEntity deleteUser(@PathVariable String targetUsername) {
        var scope = currentScope.getCurrentScope();
        String cleanTarget = targetUsername.trim();

        var rows = jdbcTemplate.queryForList(
                "SELECT user_id, role, farm_id, enabled FROM users WHERE username = ?", cleanTarget);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User '" + cleanTarget + "' does not exist."));
        }
        Map row = (Map) rows.get(0);

        var denial = UserChangeGuard.denyDelete(
                new UserChangeGuard.Actor(scope.username(), scope.role(), scope.farmId()),
                new UserChangeGuard.Target(cleanTarget, String.valueOf(row.get("role")),
                        row.get("farm_id") != null ? String.valueOf(row.get("farm_id")) : null,
                        !Boolean.FALSE.equals(row.get("enabled"))),
                enabledAdminCount());
        if (denial != null) {
            return ResponseEntity.status(denial.status()).body(Map.of("error", denial.message()));
        }

        // user_farm_scopes goes with the user (ON DELETE CASCADE); barn assignments are keyed by username.
        jdbcTemplate.update("DELETE FROM user_barn_assignments WHERE username = ?", cleanTarget);
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", cleanTarget);
        return ResponseEntity.ok(Map.of("status", "DELETED", "username", cleanTarget));
    }

    private long enabledAdminCount() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'SYSTEM_ADMIN' AND enabled IS NOT FALSE", Long.class);
        return n != null ? n : 0;
    }

    /** Keeps user_farm_scopes (read by the JPA User entity) in step with users.farm_id. */
    private void syncFarmScope(String userId, String farmId) {
        jdbcTemplate.update("DELETE FROM user_farm_scopes WHERE user_id = ?", userId);
        if (farmId != null) {
            jdbcTemplate.update("""
                INSERT INTO user_farm_scopes (scope_id, user_id, farm_id)
                VALUES ('SCP-' || substr(md5(? || ':' || ?), 1, 16), ?, ?)
                ON CONFLICT (user_id, farm_id) DO NOTHING
            """, userId, farmId, userId, farmId);
        }
    }

    private List sanitizeAndValidateBarns(UserScope scope,
                                          String targetRole,
                                          String effectiveFarmId,
                                          List rawBarnIds) {
        if (!"BARN_SUPERVISOR".equals(targetRole) || rawBarnIds == null || rawBarnIds.isEmpty()) {
            return List.of();
        }
        List valid = new ArrayList();
        for (Object rawObj : rawBarnIds) {
            if (rawObj == null) continue;
            String bid = String.valueOf(rawObj).trim();
            if (bid.isEmpty()) continue;

            var barnFarms = jdbcTemplate.queryForList(
                    "SELECT farm_id FROM barns WHERE barn_id = ?", String.class, bid);
            if (barnFarms.isEmpty()) {
                throw new IllegalArgumentException("Barn does not exist: " + bid);
            }
            String barnFarmId = String.valueOf(barnFarms.get(0));
            if (effectiveFarmId != null && !effectiveFarmId.equals(barnFarmId)) {
                throw new IllegalArgumentException("Barn " + bid + " does not belong to farm " + effectiveFarmId);
            }
            if (!barnAccess.canAccessBarnId(scope, bid)) {
                throw new IllegalArgumentException("Barn " + bid + " is outside your authorized scope.");
            }
            if (!valid.contains(bid)) {
                valid.add(bid);
            }
        }
        return valid;
    }
}