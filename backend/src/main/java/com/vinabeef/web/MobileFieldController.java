package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/mobile")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class MobileFieldController {

    private final JdbcTemplate jdbcTemplate;

    @Data
    public static class MobileHealthEvent {
        private String clientEventId; // Client-generated UUID for offline idempotency
        private String animalId;
        private String tagEpc;
        private String recordType;    // VACCINATION, TREATMENT, HEALTH_CHECK, QUARANTINE
        private String healthStatus;  // HEALTHY, UNDER_TREATMENT, QUARANTINE
        private String title;
        private String medication;
        private String notes;
        private String recordedAt;    // ISO-8601 timestamp captured at the pen
        private String deviceId;
    }

    /**
     * Enhancement 3: Downloads Barn Herd & RFID Tag Cache for Offline Field Operation
     */
    @GetMapping("/sync-bundle")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    public ResponseEntity<?> downloadSyncBundle(@RequestParam(required = false, defaultValue = "") String barnId) {
        var barns = jdbcTemplate.queryForList("""
            SELECT b.barn_id, b.code, b.farm_id, f.name AS farm_name, b.current_headcount
            FROM barns b
            JOIN farms f ON b.farm_id = f.farm_id
            ORDER BY f.farm_id, b.code
        """);

        String targetBarn = barnId.trim();
        if (targetBarn.isEmpty() && !barns.isEmpty()) {
            targetBarn = String.valueOf(barns.get(0).get("barn_id"));
        }

        var cattle = jdbcTemplate.queryForList("""
            SELECT 
                a.animal_id,
                a.assigned_barn_id AS barn_id,
                b.code AS barn_code,
                COALESCE(a.breed, 'Brahman Cross') AS breed,
                COALESCE(a.weight_kg, 420.0) AS weight_kg,
                COALESCE(a.health_status, 'HEALTHY') AS health_status,
                COALESCE(rt.epc_value, '') AS tag_epc
            FROM animals a
            LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
            LEFT JOIN tag_assignments ta ON a.animal_id = ta.animal_id AND ta.valid_to IS NULL
            LEFT JOIN rfid_tags rt ON ta.tag_id = rt.tag_id
            WHERE a.lifecycle_status = 'ACTIVE'
              AND (? = 'ALL' OR a.assigned_barn_id = ?)
            ORDER BY a.animal_id ASC
            LIMIT 1500
        """, targetBarn, targetBarn);

        var protocols = List.of(
                Map.of("type", "VACCINATION", "status", "HEALTHY", "title", "Foot-and-Mouth Disease (FMD)", "defaultBatch", "FMD-2026-B2"),
                Map.of("type", "VACCINATION", "status", "HEALTHY", "title", "Lumpy Skin Disease (LSD)", "defaultBatch", "LSD-2026-A4"),
                Map.of("type", "VACCINATION", "status", "HEALTHY", "title", "Bovine Rhinotracheitis (IBR)", "defaultBatch", "IBR-2026-C1"),
                Map.of("type", "TREATMENT", "status", "UNDER_TREATMENT", "title", "Antibiotic Course (Oxytetracycline 10ml)", "defaultBatch", "OXY-200MG"),
                Map.of("type", "HEALTH_CHECK", "status", "HEALTHY", "title", "Routine Pen Clinical Check", "defaultBatch", "CHK-NORMAL"),
                Map.of("type", "QUARANTINE", "status", "QUARANTINE", "title", "Suspected Fever / Isolation Required", "defaultBatch", "ISO-01")
        );

        return ResponseEntity.ok(Map.of(
                "syncedAt", Instant.now().toString(),
                "selectedBarnId", targetBarn,
                "barns", barns,
                "cattle", cattle,
                "protocols", protocols
        ));
    }

    /**
     * Enhancement 4: Idempotent Batch Sync of Offline Mobile Vaccination & Health Records
     */
    @PostMapping("/health-sync")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "MOBILE_OFFLINE_HEALTH_SYNC", targetType = "HEALTH_RECORD")
    @Transactional
    public ResponseEntity<?> syncOfflineHealthRecords(@RequestBody List<MobileHealthEvent> records, Authentication auth) {
        if (records == null || records.isEmpty()) {
            return ResponseEntity.ok(Map.of("synced", 0, "duplicatesIgnored", 0));
        }

        String actor = auth != null ? auth.getName() : "mobile_supervisor";
        int synced = 0;
        int duplicates = 0;
        List<String> syncedIds = new ArrayList<>();

        for (MobileHealthEvent ev : records) {
            if (ev.getClientEventId() == null || ev.getClientEventId().isBlank()) {
                continue;
            }

            // 1. Strict Idempotency Check on client_event_id
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM health_records WHERE client_event_id = ?",
                    Integer.class, ev.getClientEventId().trim());
            if (existing != null && existing > 0) {
                duplicates++;
                syncedIds.add(ev.getClientEventId().trim());
                continue;
            }

            // 2. Resolve Animal ID from either animalId or scanned RFID tagEpc
            String animalId = ev.getAnimalId();
            if ((animalId == null || animalId.isBlank()) && ev.getTagEpc() != null && !ev.getTagEpc().isBlank()) {
                List<String> found = jdbcTemplate.queryForList("""
                    SELECT ta.animal_id
                    FROM tag_assignments ta
                    JOIN rfid_tags rt ON ta.tag_id = rt.tag_id
                    WHERE rt.epc_value = ? AND ta.valid_to IS NULL
                    LIMIT 1
                """, String.class, ev.getTagEpc().trim());
                if (!found.isEmpty()) {
                    animalId = found.get(0);
                }
            }

            if (animalId == null || animalId.isBlank()) {
                continue;
            }

            Instant eventTime;
            try {
                eventTime = (ev.getRecordedAt() != null && !ev.getRecordedAt().isBlank())
                        ? Instant.parse(ev.getRecordedAt())
                        : Instant.now();
            } catch (Exception e) {
                eventTime = Instant.now();
            }

            String healthId = "HLT-M-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String recordType = ev.getRecordType() != null ? ev.getRecordType().toUpperCase() : "VACCINATION";
            String healthStatus = ev.getHealthStatus() != null ? ev.getHealthStatus().toUpperCase() : "HEALTHY";
            String title = ev.getTitle() != null ? ev.getTitle() : "Field Immunization";
            String med = ev.getMedication() != null ? ev.getMedication() : "BATCH-2026";
            String notes = ev.getNotes() != null ? ev.getNotes() : "";
            String deviceId = ev.getDeviceId() != null ? ev.getDeviceId() : "ANDROID-RFID";

            jdbcTemplate.update("""
                INSERT INTO health_records
                (health_id, animal_id, record_type, administered_by, payload, event_at, client_event_id, device_id)
                VALUES (?, ?, ?, ?, jsonb_build_object('title', ?, 'medication_or_batch', ?, 'resulting_status', ?, 'notes', ?, 'source', 'MOBILE_RFID'), ?, ?, ?)
            """, healthId, animalId, recordType, actor, title, med, healthStatus, notes,
                    Timestamp.from(eventTime), ev.getClientEventId().trim(), deviceId);

            jdbcTemplate.update("""
                UPDATE animals
                SET health_status = ?,
                    quarantine_notes = CASE WHEN ? = 'HEALTHY' THEN NULL ELSE ? END
                WHERE animal_id = ?
            """, healthStatus, healthStatus, title + (notes.isEmpty() ? "" : " - " + notes), animalId);

            if ("QUARANTINE".equals(healthStatus)) {
                List<String> barns = jdbcTemplate.queryForList(
                        "SELECT assigned_barn_id FROM animals WHERE animal_id = ?", String.class, animalId);
                String barnId = barns.isEmpty() ? "BARN-DL-01" : barns.get(0);
                jdbcTemplate.update("""
                    INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
                    VALUES (?, 'HEALTH_QUARANTINE', 'CRITICAL', ?, ?, 'OPEN',
                            jsonb_build_object('condition', ?, 'medication', ?, 'reportedBy', ?, 'deviceId', ?),
                            CURRENT_TIMESTAMP)
                """, "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                        animalId, barnId, title, med, actor, deviceId);
            }

            synced++;
            syncedIds.add(ev.getClientEventId().trim());
        }

        return ResponseEntity.ok(Map.of(
                "status", "SYNC_OK",
                "synced", synced,
                "duplicatesIgnored", duplicates,
                "acknowledgedClientIds", syncedIds
        ));
    }
}