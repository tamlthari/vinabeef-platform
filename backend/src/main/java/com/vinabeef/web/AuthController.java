package com.vinabeef.web;

import com.vinabeef.security.JwtProvider;
import com.vinabeef.security.RbacScopeService;
import com.vinabeef.security.User;
import com.vinabeef.security.UserRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RbacScopeService rbacScopeService;

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req == null || req.getUsername() == null || req.getPassword() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required"));
        }

        String username = req.getUsername().trim();
        User user = userRepository.findByUsername(username).orElse(null);

        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid credentials"));
        }

        if (!user.isEnabled()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "This account is disabled. Ask an administrator to enable it."));
        }

        String role = user.getRole();

        // users.farm_id is what RbacScopeService enforces on every request, so it is also what the login reports.
        // Login used to read user_farm_scopes instead; accounts that only had users.farm_id (manager_dongnai and
        // everything created from the Users page) logged in with farm "ALL" and saw empty pages.
        List<String> farmRows = jdbcTemplate.queryForList(
                "SELECT farm_id FROM users WHERE username = ? AND farm_id IS NOT NULL", String.class, username);
        String farmId = !farmRows.isEmpty() ? farmRows.get(0)
                : (user.getAssignedFarmIds() != null && !user.getAssignedFarmIds().isEmpty()
                        ? user.getAssignedFarmIds().iterator().next() : "ALL");
        List<String> farmScopes = "ALL".equals(farmId) ? List.of() : List.of(farmId);

        List<String> assignedBarns = List.of();
        if ("BARN_SUPERVISOR".equals(role)) {
            assignedBarns = jdbcTemplate.queryForList(
                    "SELECT barn_id FROM user_barn_assignments WHERE username = ? ORDER BY barn_id",
                    String.class,
                    username
            );
        }

        String token = jwtProvider.generateToken(user);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("username", user.getUsername());
        response.put("fullName", user.getFullName());
        response.put("role", role);
        response.put("farmId", farmId);
        response.put("farm_scopes", farmScopes);
        response.put("assignedBarns", assignedBarns);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<?> currentUserScope() {
        var scope = rbacScopeService.getCurrentScope();
        String farmId = scope.farmId() != null ? scope.farmId() : "ALL";
        return ResponseEntity.ok(Map.of(
                "username", scope.username(),
                "role", scope.role(),
                "farmId", farmId,
                "farm_scopes", !"ALL".equals(farmId) ? List.of(farmId) : List.of(),
                "assignedBarns", scope.barnIds()
        ));
    }
}
