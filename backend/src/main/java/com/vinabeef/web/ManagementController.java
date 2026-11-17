package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/management")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ManagementController {

    private final JdbcTemplate jdbcTemplate;

    @Data
    public static class TransferRequest {
        private String animalId;
        private String targetBarnId;
        private String reason;
    }

    /**
     * Approves an animal transfer.
     * Enforces that the actor is a FARM_MANAGER assigned to the target farm[cite: 10, 40].
     */
    @PostMapping("/farms/{farmId}/transfers")
    @PreAuthorize("hasRole('FARM_MANAGER') and @securityService.isFarmAssigned(authentication, #farmId)")
    @Audited(action = "APPROVE_TRANSFER", targetType = "CATTLE_TRANSFER")
    public ResponseEntity<?> approveAnimalTransfer(@PathVariable String farmId,
                                                   @RequestBody TransferRequest request,
                                                   Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "status", "TRANSFER_APPROVED",
                "farmId", farmId,
                "animalId", request.getAnimalId(),
                "approvedBy", auth.getName()
        ));
    }

    /**
     * Reports on a specific farm's movement count and barn status.
     * Accessible by EXECUTIVES (all farms) or FARM_MANAGERS assigned to this farm[cite: 10, 40].
     */
    @GetMapping("/farms/{farmId}/report")
    @PreAuthorize("hasRole('EXECUTIVE') or (hasRole('FARM_MANAGER') and @securityService.isFarmAssigned(authentication, #farmId))")
    @Audited(action = "GENERATE_FARM_REPORT", targetType = "REPORT")
    public ResponseEntity<?> getFarmReport(@PathVariable String farmId) {
        List<Map<String, Object>> barns = jdbcTemplate.queryForList("""
            SELECT b.code, b.capacity, b.current_headcount, 
                   COUNT(m.movement_id) as total_movements
            FROM barns b
            LEFT JOIN gates g ON b.barn_id = g.barn_id
            LEFT JOIN movements m ON g.gate_id = m.gate_id
            WHERE b.farm_id = ?
            GROUP BY b.barn_id, b.code, b.capacity, b.current_headcount
        """, farmId);

        return ResponseEntity.ok(Map.of(
                "farmId", farmId,
                "barnData", barns
        ));
    }

    /**
     * Cross-farm executive summary.
     * Restricted to EXECUTIVE role[cite: 10, 40].
     */
    @GetMapping("/executive/summary")
    @PreAuthorize("hasRole('EXECUTIVE')")
    @Audited(action = "VIEW_EXECUTIVE_SUMMARY", targetType = "EXECUTIVE_REPORT")
    public ResponseEntity<?> getExecutiveSummary() {
        List<Map<String, Object>> summary = jdbcTemplate.queryForList("""
            SELECT f.name as farm_name, 
                   COUNT(DISTINCT b.barn_id) as total_barns,
                   COALESCE(SUM(b.current_headcount), 0) as total_cattle
            FROM farms f
            LEFT JOIN barns b ON f.farm_id = b.farm_id
            GROUP BY f.farm_id, f.name
        """);

        return ResponseEntity.ok(summary);
    }

    /**
     * Audit log viewer.
     * Restricted to SYSTEM_ADMIN per ADR-006[cite: 10, 40, 43].
     */
    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<?> getAuditLogs() {
        List<Map<String, Object>> logs = jdbcTemplate.queryForList("""
            SELECT audit_id, actor_id, action, target, outcome, details, event_at
            FROM audit_entries
            ORDER BY event_at DESC
            LIMIT 50
        """);
        return ResponseEntity.ok(logs);
    }
}