package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.security.RbacScopeService;
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
    private final RbacScopeService rbacScopeService;

    private static final Set<String> VALID_ROLES = Set.of(
            "SYSTEM_ADMIN", "FARM_MANAGER", "BARN_SUPERVISOR", "EXECUTIVE"
    );

    @Data
    public static class CreateUserRequest {
        private String username;
        private String fullName;
        private String password;
        private String role;
        private String farmId;
        private List<String> barnIds;
    }

    @Data
    public static class UpdateUserRoleRequest {
        private String role;
        private String farmId;
        private List<String> barnIds;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER')")
    public ResponseEntity<?> listUsers() {
        var scope = rbacScopeService.getCurrentScope();

        List> users;
        if (scope.isGlobal()) {
            users = jdbcTemplate.queryForList("""
                SELECT u.user_id, u.username, COALESCE(u.full_name, u.username) AS full_name,
                       u.role, COALESCE(u.farm_id, 'ALL') AS farm_id, f.name AS farm_name
                FROM users u
                LEFT JOIN farms f ON u.farm_id = f.farm_id
                ORDER BY u.role ASC, u.username ASC
            """);
        } else if (scope.isFarmScoped() && scope.farmId() != null) {
            users = jdbcTemplate.queryForList("""
                SELECT u.user_id, u.username, COALESCE(u.full_name, u.username) AS full_name,
                       u.role, COALESCE(u.farm_id, 'ALL') AS farm_id, f.name AS farm_name
                FROM users u
                LEFT JOIN farms f ON u.farm_id = f.farm_id
                WHERE u.farm_id = ?
                ORDER BY u.role ASC, u.username ASC
            """, scope.farmId());
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Insufficient privileges"));
        }

        // Attach assigned barns for each user
        var allAssignments = jdbcTemplate.queryForList("""
            SELECT uba.username, uba.barn_id, b.code AS barn_code
            FROM user_barn_assignments uba
            LEFT JOIN barns b ON uba.barn_id = b.barn_id
            ORDER BY b.code ASC
        """);

        Map<String, List>> barnsByUser = new HashMap<>();
        for (var row : allAssignments) {
            String uname = String.valueOf(row.get("username"));
            barnsByUser.computeIfAbsent(uname, k -> new ArrayList<>()).add(Map.of(
                    "barnId", String.valueOf(row.get("barn_id")),
                    "code", String.valueOf(row.get("barn_code") != null ? row.get("barn_code") : row.get("barn_id"))
            ));
        }

        List> enriched = new ArrayList<>();
        for (var u : users) {
            Map<String, Object> copy = new LinkedHashMap<>(u);
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
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest req) {
        var scope = rbacScopeService.getCurrentScope();

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

        // Farm Managers can only create users within their own farm and cannot grant enterprise-wide roles
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

        List<String> cleanBarnIds = sanitizeAndValidateBarns(scope, targetRole, effectiveFarmId, req.getBarnIds());
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

        for (String barnId : cleanBarnIds) {
            jdbcTemplate.update("""
                INSERT INTO user_barn_assignments (username, barn_id)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
            """, username, barnId);
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
    public ResponseEntity<?> updateUserRoleAndScope(@PathVariable String targetUsername,
                                                    @RequestBody UpdateUserRoleRequest req) {
        var scope = rbacScopeService.getCurrentScope();
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

        List<String> cleanBarnIds = sanitizeAndValidateBarns(scope, newRole, effectiveFarmId, req.getBarnIds());
        if ("BARN_SUPERVISOR".equals(newRole) && cleanBarnIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "A Barn Supervisor must have at least one assigned barn."));
        }

        jdbcTemplate.update("UPDATE users SET role = ?, farm_id = ? WHERE username = ?",
                newRole, effectiveFarmId, cleanTarget);

        jdbcTemplate.update("DELETE FROM user_barn_assignments WHERE username = ?", cleanTarget);
        if ("BARN_SUPERVISOR".equals(newRole)) {
            for (String barnId : cleanBarnIds) {
                jdbcTemplate.update("""
                    INSERT INTO user_barn_assignments (username, barn_id)
                    VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                """, cleanTarget, barnId);
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

    private List<String> sanitizeAndValidateBarns(RbacScopeService.UserScope scope,
                                                  String targetRole,
                                                  String effectiveFarmId,
                                                  List<String> rawBarnIds) {
        if (!"BARN_SUPERVISOR".equals(targetRole) || rawBarnIds == null || rawBarnIds.isEmpty()) {
            return List.of();
        }
        List<String> valid = new ArrayList<>();
        for (String rawId : rawBarnIds) {
            if (rawId == null || rawId.isBlank()) continue;
            String bid = rawId.trim();
            var barnFarms = jdbcTemplate.queryForList(
                    "SELECT farm_id FROM barns WHERE barn_id = ?", String.class, bid);
            if (barnFarms.isEmpty()) {
                throw new IllegalArgumentException("Barn does not exist: " + bid);
            }
            String barnFarmId = barnFarms.get(0);
            if (effectiveFarmId != null && !effectiveFarmId.equals(barnFarmId)) {
                throw new IllegalArgumentException("Barn " + bid + " does not belong to farm " + effectiveFarmId);
            }
            if (!rbacScopeService.canAccessBarnId(scope, bid)) {
                throw new IllegalArgumentException("Barn " + bid + " is outside your authorized scope.");
            }
            if (!valid.contains(bid)) {
                valid.add(bid);
            }
        }
        return valid;
    }
}