package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.security.BarnAccessPolicy;
import com.vinabeef.security.CurrentScopeProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Endpoints of the field app: a supervisor walks the pen with a handheld RFID reader, scans an ear tag and
 * records a vaccination or health event on the spot, with or without network.
 */
@RestController
@RequestMapping("/api/v1/mobile")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class MobileFieldController {

    private static final Set<String> RECORD_TYPES = Set.of("VACCINATION", "TREATMENT", "HEALTH_CHECK", "QUARANTINE");
    private static final Set<String> STATUSES = Set.of("HEALTHY", "UNDER_TREATMENT", "QUARANTINE");
    private static final Set<String> ROUTES = Set.of("SC", "IM", "ORAL", "TOPICAL", "OTHER");
    private static final double MAX_DOSE_ML = 50.0;
    private static final int MAX_CATTLE = 1500;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentScopeProvider currentScope;
    private final BarnAccessPolicy barnAccess;
    private final ObjectMapper objectMapper;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MobileHealthEvent {
        private String clientEventId; // Client-generated UUID for offline idempotency
        private String animalId;
        private String tagEpc;
        private String recordType;    // VACCINATION, TREATMENT, HEALTH_CHECK, QUARANTINE
        private String healthStatus;  // HEALTHY, UNDER_TREATMENT, QUARANTINE
        private String title;         // vaccine name or diagnosis
        private String medication;    // batch / lot (older app versions also put the dose here)
        private String notes;
        private String recordedAt;    // ISO-8601 timestamp captured at the pen
        private String deviceId;
        // Structured vaccination fields
        private String protocolId;
        private String batchNumber;   // lot of the vial; wins over 'medication' when present
        private Double doseMl;
        private String route;         // SC, IM, ORAL, TOPICAL, OTHER
        private String nextDueDate;   // yyyy-MM-dd
        private String scanMethod;    // BLE_RFID, NFC, MANUAL, DEMO
        private String scannedAt;     // ISO-8601, when the tag was read
        private String appVersion;
        private String recordedBy;    // username signed in on the phone when the record was saved
    }

    /**
     * Barn herd, RFID tags, protocols and recent vaccinations for offline use in the barn the user may access.
     * {@code barnId} may be empty (first barn of the user), a barn id, or {@code ALL} (every barn of the user).
     */
    @GetMapping("/sync-bundle")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    public ResponseEntity<?> downloadSyncBundle(@RequestParam(required = false, defaultValue = "") String barnId) {
        var scope = currentScope.getCurrentScope();

        List<Map<String, Object>> barns = new ArrayList<>();
        for (Map<String, Object> b : jdbcTemplate.queryForList("""
            SELECT b.barn_id, b.code, b.farm_id, f.name AS farm_name,
                   COALESCE(c.in_barn_count, 0)     AS current_headcount,
                   COALESCE(c.assigned_count, 0)    AS assigned_count,
                   COALESCE(c.out_pasture_count, 0) AS out_pasture_count
            FROM barns b
            JOIN farms f ON b.farm_id = f.farm_id
            LEFT JOIN v_barn_counts c ON c.barn_id = b.barn_id
            ORDER BY f.farm_id, b.code
        """)) {
            if (scope.canAccessBarn(String.valueOf(b.get("barn_id")), String.valueOf(b.get("farm_id")))) {
                barns.add(b);
            }
        }

        String requested = barnId.trim();
        List<String> barnIds = new ArrayList<>();
        String targetBarn;
        if ("ALL".equalsIgnoreCase(requested)) {
            targetBarn = "ALL";
            barns.forEach(b -> barnIds.add(String.valueOf(b.get("barn_id"))));
        } else {
            targetBarn = requested;
            if (targetBarn.isEmpty() && !barns.isEmpty()) {
                targetBarn = String.valueOf(barns.get(0).get("barn_id"));
            }
            final String wanted = targetBarn;
            if (barns.stream().anyMatch(b -> wanted.equals(String.valueOf(b.get("barn_id"))))) {
                barnIds.add(targetBarn);
            }
        }

        List<Map<String, Object>> cattle = List.of();
        List<Map<String, Object>> vaccinations = List.of();
        int cattleTotal = 0;
        if (!barnIds.isEmpty()) {
            String in = String.join(",", Collections.nCopies(barnIds.size(), "?"));
            Object[] args = barnIds.toArray();

            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM animals WHERE lifecycle_status = 'ACTIVE' AND assigned_barn_id IN (" + in + ")",
                    Integer.class, args);
            cattleTotal = total != null ? total : 0;

            cattle = jdbcTemplate.queryForList("""
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
                  AND a.assigned_barn_id IN (%s)
                ORDER BY a.animal_id ASC
                LIMIT %d
            """.formatted(in, MAX_CATTLE), args);

            vaccinations = jdbcTemplate.queryForList("""
                SELECT hr.animal_id,
                       COALESCE(hr.payload->>'vaccine', hr.payload->>'title') AS title,
                       to_char(MAX(hr.event_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS last_at
                FROM health_records hr
                JOIN animals a ON a.animal_id = hr.animal_id
                WHERE hr.record_type = 'VACCINATION'
                  AND a.lifecycle_status = 'ACTIVE'
                  AND a.assigned_barn_id IN (%s)
                GROUP BY hr.animal_id, COALESCE(hr.payload->>'vaccine', hr.payload->>'title')
            """.formatted(in), args);
        }

        var protocols = jdbcTemplate.queryForList("""
            SELECT protocol_id, record_type AS type, resulting_status AS status, title,
                   default_dose_ml, route, interval_days
            FROM health_protocols
            WHERE active
            ORDER BY sort_order, title
        """);

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("syncedAt", Instant.now().toString());
        bundle.put("selectedBarnId", targetBarn);
        bundle.put("barns", barns);
        bundle.put("cattle", cattle);
        bundle.put("cattleTotal", cattleTotal);
        bundle.put("cattleTruncated", cattleTotal > cattle.size());
        bundle.put("protocols", protocols);
        bundle.put("recentVaccinations", vaccinations);
        return ResponseEntity.ok(bundle);
    }

    /**
     * Idempotent batch sync of records made in the field. Every record gets an answer: its client id is in
     * {@code acknowledgedClientIds} when it is stored (or was stored before), or in {@code rejected} with a
     * reason code. The app deletes acknowledged records and shows the rejected ones to the supervisor.
     */
    @PostMapping("/health-sync")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "MOBILE_OFFLINE_HEALTH_SYNC", targetType = "HEALTH_RECORD")
    @Transactional
    public ResponseEntity<?> syncOfflineHealthRecords(@RequestBody List<MobileHealthEvent> records, Authentication auth) {
        if (records == null || records.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "SYNC_OK", "synced", 0, "duplicatesIgnored", 0,
                    "acknowledgedClientIds", List.of(), "rejected", List.of()));
        }

        var scope = currentScope.getCurrentScope();
        String actor = auth != null ? auth.getName() : "mobile_supervisor";
        int synced = 0;
        int duplicates = 0;
        List<String> ackIds = new ArrayList<>();
        List<Map<String, String>> rejected = new ArrayList<>();

        for (MobileHealthEvent ev : records) {
            String clientId = ev.getClientEventId() == null ? "" : ev.getClientEventId().trim();
            if (clientId.isEmpty()) {
                rejected.add(reject("", "MISSING_CLIENT_EVENT_ID", "The record has no client event id."));
                continue;
            }

            // 1. Strict idempotency on client_event_id
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM health_records WHERE client_event_id = ?", Integer.class, clientId);
            if (existing != null && existing > 0) {
                duplicates++;
                ackIds.add(clientId);
                continue;
            }

            // 1b. A record saved under one account is accepted only from that account, so a phone that is
            //     signed in as someone else the next day cannot put its own name on it.
            if (!fromRightAccount(ev.getRecordedBy(), actor)) {
                String author = ev.getRecordedBy().trim();
                rejected.add(reject(clientId, "WRONG_USER",
                        "This record was saved by '" + author + "'. Sign in as " + author + " to send it."));
                continue;
            }

            // 2. Validate what was entered
            String recordType = ev.getRecordType() != null ? ev.getRecordType().trim().toUpperCase() : "VACCINATION";
            String healthStatus = ev.getHealthStatus() != null ? ev.getHealthStatus().trim().toUpperCase() : "HEALTHY";
            String title = ev.getTitle() != null ? ev.getTitle().trim() : "";
            String batch = firstNonBlank(ev.getBatchNumber(), ev.getMedication());
            String route = ev.getRoute() != null && !ev.getRoute().isBlank() ? ev.getRoute().trim().toUpperCase() : null;

            String problem = null;
            String code = "INVALID_RECORD";
            if (!RECORD_TYPES.contains(recordType)) {
                problem = "Unknown record type '" + recordType + "'.";
            } else if (!STATUSES.contains(healthStatus)) {
                problem = "Unknown health status '" + healthStatus + "'.";
            } else if (title.isEmpty()) {
                problem = "The vaccine or diagnosis is missing.";
            } else if ("VACCINATION".equals(recordType) && batch.isEmpty()) {
                code = "MISSING_BATCH";
                problem = "A vaccination needs the batch / lot number of the vial.";
            } else if (ev.getDoseMl() != null && (ev.getDoseMl() <= 0 || ev.getDoseMl() > MAX_DOSE_ML)) {
                code = "INVALID_DOSE";
                problem = "The dose must be above 0 and at most " + MAX_DOSE_ML + " ml.";
            } else if (route != null && !ROUTES.contains(route)) {
                problem = "Unknown route '" + route + "'.";
            }
            if (problem != null) {
                rejected.add(reject(clientId, code, problem));
                continue;
            }

            // 3. Resolve the animal from its id, or from the scanned RFID tag
            String animalId = ev.getAnimalId() != null ? ev.getAnimalId().trim() : "";
            if (animalId.isEmpty() && ev.getTagEpc() != null && !ev.getTagEpc().isBlank()) {
                List<String> found = jdbcTemplate.queryForList("""
                    SELECT ta.animal_id
                    FROM tag_assignments ta
                    JOIN rfid_tags rt ON ta.tag_id = rt.tag_id
                    WHERE rt.epc_value = ? AND ta.valid_to IS NULL
                    LIMIT 1
                """, String.class, ev.getTagEpc().trim().toUpperCase());
                if (!found.isEmpty()) {
                    animalId = found.get(0);
                }
            }
            if (animalId.isEmpty()) {
                rejected.add(reject(clientId, "UNKNOWN_ANIMAL",
                        "No animal is registered for tag '" + (ev.getTagEpc() == null ? "" : ev.getTagEpc()) + "'."));
                continue;
            }
            List<String> barns = jdbcTemplate.queryForList(
                    "SELECT assigned_barn_id FROM animals WHERE animal_id = ?", String.class, animalId);
            if (barns.isEmpty()) {
                rejected.add(reject(clientId, "UNKNOWN_ANIMAL", "Animal '" + animalId + "' does not exist."));
                continue;
            }
            String barnId = barns.get(0);
            if (!barnAccess.canAccessBarnId(scope, barnId)) {
                rejected.add(reject(clientId, "OUT_OF_SCOPE", "Animal '" + animalId + "' is in a barn you cannot record for."));
                continue;
            }

            Instant eventTime = parseInstant(ev.getRecordedAt(), Instant.now());
            String deviceId = ev.getDeviceId() != null ? ev.getDeviceId() : "ANDROID-RFID";
            String notes = ev.getNotes() != null ? ev.getNotes() : "";

            // 4. The record. The payload keeps the keys the web app already reads (title, medication_or_batch,
            //    resulting_status, notes, source) and adds the structured vaccination fields.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title);
            payload.put("medication_or_batch", batch);
            payload.put("resulting_status", healthStatus);
            payload.put("notes", notes);
            payload.put("source", "MOBILE_RFID");
            if ("VACCINATION".equals(recordType)) {
                // the keys the web app writes for a vaccination (CattleManagementController), so one query reads both
                payload.put("vaccine", title);
                payload.put("batch", batch);
            }
            payload.put("protocol_id", blankToNull(ev.getProtocolId()));
            payload.put("batch_number", blankToNull(batch));
            payload.put("dose_ml", ev.getDoseMl());
            payload.put("route", route);
            payload.put("next_due_date", validDate(ev.getNextDueDate()));
            payload.put("scan_method", blankToNull(ev.getScanMethod()));
            payload.put("scanned_tag", blankToNull(ev.getTagEpc()));
            payload.put("scanned_at", blankToNull(ev.getScannedAt()));
            payload.put("app_version", blankToNull(ev.getAppVersion()));
            payload.put("synced_at", Instant.now().toString());

            String healthId = "HLT-M-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            jdbcTemplate.update("""
                INSERT INTO health_records
                (health_id, animal_id, record_type, administered_by, payload, event_at, client_event_id, device_id)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            """, healthId, animalId, recordType, actor, objectMapper.writeValueAsString(payload),
                    Timestamp.from(eventTime), clientId, deviceId);

            // 5. A vaccination records what was given. It says nothing about the animal's condition, so it must
            //    not clear a quarantine or a treatment. Other record types set the resulting status.
            if (!"VACCINATION".equals(recordType)) {
                jdbcTemplate.update("""
                    UPDATE animals
                    SET health_status = ?,
                        quarantine_notes = CASE WHEN ? = 'HEALTHY' THEN NULL ELSE ? END
                    WHERE animal_id = ?
                """, healthStatus, healthStatus, title + (notes.isEmpty() ? "" : " - " + notes), animalId);
            }

            if ("QUARANTINE".equals(healthStatus)) {
                jdbcTemplate.update("""
                    INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
                    VALUES (?, 'HEALTH_QUARANTINE', 'CRITICAL', ?, ?, 'OPEN',
                            jsonb_build_object('condition', ?, 'medication', ?, 'reportedBy', ?, 'deviceId', ?),
                            CURRENT_TIMESTAMP)
                """, "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                        animalId, barnId, title, batch, actor, deviceId);
            }

            synced++;
            ackIds.add(clientId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SYNC_OK");
        response.put("synced", synced);
        response.put("duplicatesIgnored", duplicates);
        response.put("acknowledgedClientIds", ackIds);
        response.put("rejected", rejected);
        return ResponseEntity.ok(response);
    }

    /**
     * True when the record may be accepted from this account. A record with no author (older app versions,
     * or saved before anyone had signed in) is accepted from any account; otherwise the account must be the author.
     */
    static boolean fromRightAccount(String recordedBy, String actor) {
        return recordedBy == null || recordedBy.isBlank() || recordedBy.trim().equalsIgnoreCase(actor);
    }

    private static Map<String, String> reject(String clientId, String code, String message) {
        return Map.of("clientEventId", clientId, "code", code, "message", message);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b != null ? b.trim() : "";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Instant parseInstant(String value, Instant fallback) {
        try {
            return value != null && !value.isBlank() ? Instant.parse(value.trim()) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String validDate(String value) {
        try {
            return value != null && !value.isBlank() ? LocalDate.parse(value.trim()).toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
