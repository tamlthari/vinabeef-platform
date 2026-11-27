package com.vinabeef.web;

// import com.fasterxml.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectMapper;

import com.vinabeef.audit.Audited;
import com.vinabeef.security.BarnAccessPolicy;
import com.vinabeef.security.CurrentScopeProvider;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/cattle")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@SuppressWarnings("unchecked")
public class CattleManagementController {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentScopeProvider currentScope;
    private final BarnAccessPolicy barnAccess;
    private final ReportClock clock;

    @Data
    public static class CreateCattleRequest {
        private String animalId;
        private String barnId;
        private String breed;
        private Double weightKg;
        private String tagEpc;
        private String credentialIdentifier;
    }

    /** Fields that are left out (null) keep their value. The barn changes through the transfer, the tag through the tag endpoint. */
    @Data
    public static class UpdateCattleRequest {
        private String breed;
        private Double weightKg;
    }

    @Data
    public static class AssignTagRequest {
        private String tagEpc;
        private String credentialIdentifier;
    }

    @Data
    public static class VaccinationRequest {
        private String vaccineName;
        private String batchNumber;
        private String notes;
    }

    @Data
    public static class ClinicalHealthRequest {
        private String recordType;   // VACCINATION, TREATMENT, HEALTH_CHECK, QUARANTINE
        private String healthStatus; // HEALTHY, UNDER_TREATMENT, QUARANTINE
        private String title;
        private String medication;
        private String notes;
    }

    @Data
    public static class AnimalTransferRequest {
        private String toBarnId;
        private String reason;
    }

    @GetMapping
    public ResponseEntity<?> listCattle(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "100") int size,
            @RequestParam(required = false, defaultValue = "") String sort,
            @RequestParam(required = false, defaultValue = "") String dir,
            @RequestParam(required = false, defaultValue = "") String farmId,
            @RequestParam(required = false, defaultValue = "") String barnId,
            @RequestParam(required = false, defaultValue = "") String health,
            @RequestParam(required = false, defaultValue = "") String presence,
            @RequestParam(required = false, defaultValue = "") String tags,
            @RequestParam(required = false, defaultValue = "") String vaccination,
            @RequestParam(required = false, defaultValue = "") String passes) {
        try {
            var scope = currentScope.getCurrentScope();
            int safePage = Math.max(1, page);
            int safeSize = Math.min(Math.max(1, size), 100);
            int offset = (safePage - 1) * safeSize;

            ReportQueries.Scope visible = ReportQueries.Scope.of(scope);
            String sortKey = SortSpec.effectiveKey(sort, ListQueries.CATTLE_SORT, "created");
            String sortDir = SortSpec.effectiveDir(sort, dir, ListQueries.CATTLE_SORT, "created", "desc");
            if (visible.seesNothing()) {
                return ResponseEntity.ok()
                        .header("X-Total-Count", "0")
                        .header("X-Sort-Key", sortKey)
                        .header("X-Sort-Dir", sortDir)
                        .header("Access-Control-Expose-Headers", "X-Total-Count, X-Sort-Key, X-Sort-Dir")
                        .body(List.of());
            }

            // the column filters only narrow what the person may see: their scope is applied first, inside the statement
            var filter = ListQueries.CattleFilter.parse(farmId, barnId, health, presence, tags, vaccination, passes);
            var count = ListQueries.cattleCount(visible, q, filter);
            Long totalCount = jdbcTemplate.queryForObject(count.sql(), Long.class, count.params().toArray());

            var pageQuery = ListQueries.cattlePage(visible, q, filter, sort, dir, safeSize, offset);
            var cattle = jdbcTemplate.queryForList(pageQuery.sql(), pageQuery.params().toArray());

            return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(totalCount != null ? totalCount : cattle.size()))
                    .header("X-Sort-Key", sortKey)
                    .header("X-Sort-Dir", sortDir)
                    .header("Access-Control-Expose-Headers", "X-Total-Count, X-Sort-Key, X-Sort-Dir")
                    .body(cattle);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "REGISTER_CATTLE", targetType = "ANIMAL")
    @Transactional
    public ResponseEntity<?> registerCattle(@RequestBody CreateCattleRequest req) {
        if (req.getAnimalId() == null || req.getAnimalId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Animal ID is required"));
        }

        var scope = currentScope.getCurrentScope();
        if (!barnAccess.canAccessBarnId(scope, req.getBarnId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access denied: Target barn is outside your assigned scope."));
        }

        String animalId = req.getAnimalId().trim();
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM animals WHERE animal_id = ?", Integer.class, animalId);
        if (exists != null && exists > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Animal ID '" + animalId + "' already exists"
                    + (isArchived(animalId) ? " (archived: its history is kept, so the ID cannot be reused)" : "")));
        }

        jdbcTemplate.update("""
            INSERT INTO animals (animal_id, assigned_barn_id, lifecycle_status, health_status, breed, weight_kg)
            VALUES (?, ?, 'ACTIVE', 'HEALTHY', ?, ?)
        """, animalId, req.getBarnId(),
                req.getBreed() != null && !req.getBreed().isBlank() ? req.getBreed() : "Brahman Cross",
                req.getWeightKg() != null ? req.getWeightKg() : 420.0);

        if (req.getTagEpc() != null && !req.getTagEpc().isBlank()) {
            bindTagToAnimal(animalId, req.getTagEpc().trim(), req.getCredentialIdentifier());
        }

        return ResponseEntity.ok(Map.of("status", "CREATED", "animalId", animalId));
    }

    @GetMapping("/{animalId}")
    public ResponseEntity<?> getCattleDetails(@PathVariable String animalId) {
        var profiles = jdbcTemplate.queryForList("""
            SELECT 
                a.animal_id,
                a.assigned_barn_id,
                a.lifecycle_status,
                COALESCE(a.health_status, 'HEALTHY') AS health_status,
                COALESCE(a.quarantine_notes, '') AS quarantine_notes,
                COALESCE(a.breed, 'Brahman Cross') AS breed,
                COALESCE(a.weight_kg, 420.0) AS weight_kg,
                b.code AS barn_code,
                f.farm_id,
                f.name AS farm_name,
                COALESCE(ps.presence_status, 'OUT_PASTURE') AS presence_status,
                ps.updated_at AS last_presence_update
            FROM animals a
            LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
            LEFT JOIN farms f ON b.farm_id = f.farm_id
            LEFT JOIN presence_states ps ON a.animal_id = ps.animal_id
            WHERE a.animal_id = ?
        """, animalId);

        if (profiles.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var scope = currentScope.getCurrentScope();
        String animalBarnId = (String) profiles.get(0).get("assigned_barn_id");
        String animalFarmId = (String) profiles.get(0).get("farm_id");
        if (!scope.canAccessBarn(animalBarnId, animalFarmId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access denied: Animal belongs to another farm or barn."));
        }

        Map profile = new LinkedHashMap(profiles.get(0));

        var tags = jdbcTemplate.queryForList("""
            SELECT ta.assignment_id, rt.tag_id, rt.epc_value, rt.credential_identifier, rt.status, ta.valid_from, ta.valid_to
            FROM tag_assignments ta
            JOIN rfid_tags rt ON ta.tag_id = rt.tag_id
            WHERE ta.animal_id = ?
            ORDER BY ta.valid_from DESC
        """, animalId);

        var healthRecords = jdbcTemplate.queryForList("""
            SELECT health_id, record_type, administered_by, payload::text AS payload, event_at
            FROM health_records
            WHERE animal_id = ?
            ORDER BY event_at DESC
        """, animalId);

        var movements = jdbcTemplate.queryForList("""
            SELECT m.movement_id, m.direction, m.crossing_status, m.occurred_at, g.gate_id, g.label AS gate_label, g.reader_name
            FROM movements m
            LEFT JOIN gates g ON m.gate_id = g.gate_id
            WHERE m.animal_id = ?
            ORDER BY m.occurred_at DESC
            LIMIT 50
        """, animalId);

        var transfers = jdbcTemplate.queryForList("""
            SELECT 
                t.transfer_id,
                t.transfer_type,
                t.reason,
                t.approved_by,
                t.transferred_at,
                t.from_barn_id,
                fb.code AS from_barn_code,
                ff.name AS from_farm_name,
                t.to_barn_id,
                tb.code AS to_barn_code,
                tf.name AS to_farm_name
            FROM animal_transfers t
            LEFT JOIN barns fb ON t.from_barn_id = fb.barn_id
            LEFT JOIN farms ff ON t.from_farm_id = ff.farm_id
            LEFT JOIN barns tb ON t.to_barn_id = tb.barn_id
            LEFT JOIN farms tf ON t.to_farm_id = tf.farm_id
            WHERE t.animal_id = ?
            ORDER BY t.transferred_at DESC
        """, animalId);

        // IN / OUT passes: lifetime and the farm's local day (CURRENT_DATE would cut the day at UTC midnight)
        var today = clock.today();
        var passes = jdbcTemplate.queryForMap("""
            SELECT COUNT(*) FILTER (WHERE direction = 'IN')  AS in_count,
                   COUNT(*) FILTER (WHERE direction = 'OUT') AS out_count,
                   COUNT(*) FILTER (WHERE direction = 'IN'  AND occurred_at >= ? AND occurred_at < ?) AS today_in,
                   COUNT(*) FILTER (WHERE direction = 'OUT' AND occurred_at >= ? AND occurred_at < ?) AS today_out,
                   MAX(occurred_at) AS last_pass_at
            FROM movements
            WHERE animal_id = ?
        """, java.sql.Timestamp.from(today.fromInclusive()), java.sql.Timestamp.from(today.toExclusive()),
                java.sql.Timestamp.from(today.fromInclusive()), java.sql.Timestamp.from(today.toExclusive()), animalId);

        long todayIn = passes.get("today_in") instanceof Number n ? n.longValue() : 0L;
        long todayOut = passes.get("today_out") instanceof Number n ? n.longValue() : 0L;
        profile.put("todayMovements", todayIn + todayOut);
        profile.put("inCount", passes.get("in_count"));
        profile.put("outCount", passes.get("out_count"));
        profile.put("todayIn", todayIn);
        profile.put("todayOut", todayOut);
        profile.put("lastPassAt", passes.get("last_pass_at"));
        String activeEpc = "Unassigned";
        for (Map t : tags) {
            if (t.get("valid_to") == null) {
                activeEpc = String.valueOf(t.get("epc_value"));
                break;
            }
        }
        profile.put("activeTagEpc", activeEpc);

        return ResponseEntity.ok(Map.of(
                "profile", profile,
                "tags", tags,
                "vaccinations", healthRecords,
                "movements", movements,
                "transfers", transfers
        ));
    }

    @PostMapping("/{animalId}/tags")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "ASSIGN_RFID_TAG", targetType = "RFID_TAG")
    @Transactional
    public ResponseEntity<?> assignTag(@PathVariable String animalId, @RequestBody AssignTagRequest req) {
        if (!canAccessAnimal(animalId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied for this animal."));
        }
        if (isArchived(animalId)) {
            return archivedConflict(animalId);
        }
        if (req.getTagEpc() == null || req.getTagEpc().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "RFID Tag EPC is required"));
        }
        bindTagToAnimal(animalId, req.getTagEpc().trim(), req.getCredentialIdentifier());
        return ResponseEntity.ok(Map.of("status", "TAG_ASSIGNED", "animalId", animalId, "tagEpc", req.getTagEpc().trim()));
    }

    @PostMapping("/{animalId}/vaccinations")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "RECORD_VACCINATION", targetType = "HEALTH_RECORD")
    @Transactional
    public ResponseEntity<?> recordVaccination(@PathVariable String animalId,
                                               @RequestBody VaccinationRequest req,
                                               Authentication auth) {
        if (!canAccessAnimal(animalId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied for this animal."));
        }
        if (isArchived(animalId)) {
            return archivedConflict(animalId);
        }
        String healthId = "HLT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String actor = auth != null ? auth.getName() : "Veterinary Staff";
        String vaccine = req.getVaccineName() != null ? req.getVaccineName() : "Routine Immunization";
        String batch = req.getBatchNumber() != null ? req.getBatchNumber() : "BATCH-2026";
        String notes = req.getNotes() != null ? req.getNotes() : "";

        jdbcTemplate.update("""
            INSERT INTO health_records (health_id, animal_id, record_type, administered_by, payload, event_at)
            VALUES (?, ?, 'VACCINATION', ?, jsonb_build_object('vaccine', ?, 'batch', ?, 'notes', ?), ?)
        """, healthId, animalId, actor, vaccine, batch, notes, java.sql.Timestamp.from(Instant.now()));   // pgjdbc cannot bind a raw Instant

        return ResponseEntity.ok(Map.of("status", "RECORDED", "healthId", healthId));
    }

    @PostMapping("/{animalId}/health")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "RECORD_CLINICAL_HEALTH", targetType = "HEALTH_RECORD")
    @Transactional
    public ResponseEntity<?> recordClinicalHealth(@PathVariable String animalId,
                                                  @RequestBody ClinicalHealthRequest req,
                                                  Authentication auth) {
        if (!canAccessAnimal(animalId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied for this animal."));
        }
        if (isArchived(animalId)) {
            return archivedConflict(animalId);
        }

        String healthId = "HLT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String actor = auth != null ? auth.getName() : "Veterinary Staff";
        String recordType = (req.getRecordType() != null && !req.getRecordType().isBlank())
                ? req.getRecordType().trim().toUpperCase() : "HEALTH_CHECK";
        String newStatus = (req.getHealthStatus() != null && !req.getHealthStatus().isBlank())
                ? req.getHealthStatus().trim().toUpperCase() : "HEALTHY";
        String title = req.getTitle() != null ? req.getTitle().trim() : "Clinical Evaluation";
        String medication = req.getMedication() != null ? req.getMedication().trim() : "N/A";
        String notes = req.getNotes() != null ? req.getNotes().trim() : "";

        jdbcTemplate.update("""
            INSERT INTO health_records (health_id, animal_id, record_type, administered_by, payload, event_at)
            VALUES (?, ?, ?, ?, jsonb_build_object('title', ?, 'medication_or_batch', ?, 'resulting_status', ?, 'notes', ?), ?)
        """, healthId, animalId, recordType, actor, title, medication, newStatus, notes, java.sql.Timestamp.from(Instant.now()));   // pgjdbc cannot bind a raw Instant: the treatment was never saved

        jdbcTemplate.update("""
            UPDATE animals
            SET health_status = ?,
                quarantine_notes = CASE WHEN ? = 'HEALTHY' THEN NULL ELSE ? END
            WHERE animal_id = ?
        """, newStatus, newStatus, title + (notes.isEmpty() ? "" : " - " + notes), animalId);

        if ("QUARANTINE".equals(newStatus)) {
            List barns = jdbcTemplate.queryForList("SELECT assigned_barn_id FROM animals WHERE animal_id = ?", String.class, animalId);
            String barnId = barns.isEmpty() ? "BARN-DL-01" : String.valueOf(barns.get(0));
            jdbcTemplate.update("""
                INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
                VALUES (?, 'HEALTH_QUARANTINE', 'CRITICAL', ?, ?, 'OPEN',
                        jsonb_build_object('condition', ?, 'medication', ?, 'reportedBy', ?, 'notes', ?),
                        CURRENT_TIMESTAMP)
            """, "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    animalId, barnId, title, medication, actor, notes);
        }

        return ResponseEntity.ok(Map.of("status", "UPDATED", "healthId", healthId, "healthStatus", newStatus));
    }

    @PostMapping("/{animalId}/transfer")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "TRANSFER_ANIMAL", targetType = "ANIMAL_TRANSFER")
    @Transactional
    public ResponseEntity<?> transferAnimal(@PathVariable String animalId,
                                            @RequestBody AnimalTransferRequest req,
                                            Authentication auth) {
        if (!canAccessAnimal(animalId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied for this animal."));
        }
        if (isArchived(animalId)) {
            return archivedConflict(animalId);
        }
        if (req.getToBarnId() == null || req.getToBarnId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Destination barn is required"));
        }

        var currentRows = jdbcTemplate.queryForList("""
            SELECT a.assigned_barn_id, b.farm_id AS from_farm_id, COALESCE(ps.presence_status, 'OUT_PASTURE') AS presence_status
            FROM animals a
            LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
            LEFT JOIN presence_states ps ON a.animal_id = ps.animal_id
            WHERE a.animal_id = ?
        """, animalId);

        if (currentRows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String fromBarnId = (String) currentRows.get(0).get("assigned_barn_id");
        String fromFarmId = (String) currentRows.get(0).get("from_farm_id");
        String presenceStatus = (String) currentRows.get(0).get("presence_status");
        String toBarnId = req.getToBarnId().trim();

        var destRows = jdbcTemplate.queryForList("SELECT farm_id FROM barns WHERE barn_id = ?", toBarnId);
        if (destRows.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Destination barn not found"));
        }
        String toFarmId = (String) destRows.get(0).get("farm_id");

        var scope = currentScope.getCurrentScope();
        if (scope.isBarnScoped() && !barnAccess.canAccessBarnId(scope, toBarnId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Barn Supervisors can only transfer cattle between their own assigned barns."));
        }

        String transferType = (fromFarmId != null && !fromFarmId.equals(toFarmId)) ? "INTER_FARM" : "INTER_BARN";
        String actor = auth != null ? auth.getName() : "farm_manager";
        String transferId = "TRF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        jdbcTemplate.update("""
            INSERT INTO animal_transfers (transfer_id, animal_id, from_barn_id, from_farm_id, to_barn_id, to_farm_id, transfer_type, reason, approved_by, transferred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """, transferId, animalId, fromBarnId, fromFarmId, toBarnId, toFarmId, transferType,
                req.getReason() != null && !req.getReason().isBlank() ? req.getReason().trim() : "Operational herd relocation",
                actor);

        jdbcTemplate.update("UPDATE animals SET assigned_barn_id = ? WHERE animal_id = ?", toBarnId, animalId);

        if ("IN_BARN".equals(presenceStatus) && fromBarnId != null && !fromBarnId.equals(toBarnId)) {
            jdbcTemplate.update("UPDATE barns SET current_headcount = GREATEST(0, current_headcount - 1) WHERE barn_id = ?", fromBarnId);
            jdbcTemplate.update("UPDATE barns SET current_headcount = current_headcount + 1 WHERE barn_id = ?", toBarnId);
            jdbcTemplate.update("UPDATE presence_states SET current_barn_id = ?, updated_at = CURRENT_TIMESTAMP WHERE animal_id = ?", toBarnId, animalId);
        }

        return ResponseEntity.ok(Map.of("status", "TRANSFERRED", "transferId", transferId, "transferType", transferType));
    }

    /** Edits the descriptive fields of an animal. Fields that are not sent keep their value. */
    @PutMapping("/{animalId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "UPDATE_CATTLE", targetType = "ANIMAL")
    @Transactional
    public ResponseEntity<?> updateCattle(@PathVariable String animalId, @RequestBody UpdateCattleRequest req) {
        if (!animalExists(animalId)) {
            return ResponseEntity.notFound().build();
        }
        if (!canAccessAnimal(animalId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied for this animal."));
        }
        if (isArchived(animalId)) {
            return archivedConflict(animalId);
        }

        String breed = req.getBreed() != null && !req.getBreed().isBlank() ? req.getBreed().trim() : null;
        if (breed != null && breed.length() > 80) {
            return ResponseEntity.badRequest().body(Map.of("error", "Breed can be at most 80 characters."));
        }
        Double weight = req.getWeightKg();
        if (weight != null && (weight.isNaN() || weight <= 0 || weight > 2000)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Weight must be between 0 and 2000 kg."));
        }
        if (breed == null && weight == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nothing to update: send a breed or a weight."));
        }

        jdbcTemplate.update("""
            UPDATE animals
            SET breed = COALESCE(?, breed),
                weight_kg = COALESCE(?, weight_kg)
            WHERE animal_id = ? AND lifecycle_status <> 'ARCHIVED'
        """, breed, weight, animalId);

        return ResponseEntity.ok(Map.of("status", "UPDATED", "animalId", animalId));
    }

    /**
     * Removes an animal from the herd. An animal that was only registered (no movement, health record, transfer or
     * alert) is deleted. An animal with history is archived instead: the record stays for traceability, but the
     * animal leaves the herd, its tag is released, it is taken out of the barn headcount and its open alerts are
     * closed. The response says which of the two happened.
     */
    @DeleteMapping("/{animalId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "DELETE_CATTLE", targetType = "ANIMAL")
    @Transactional
    public ResponseEntity<?> deleteCattle(@PathVariable String animalId,
                                          @RequestParam(required = false) String reason) {
        if (!animalExists(animalId) || isArchived(animalId)) {
            return ResponseEntity.notFound().build();
        }
        if (!canAccessAnimal(animalId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied for this animal."));
        }

        Integer history = jdbcTemplate.queryForObject("""
            SELECT (SELECT COUNT(*) FROM movements WHERE animal_id = ?)
                 + (SELECT COUNT(*) FROM health_records WHERE animal_id = ?)
                 + (SELECT COUNT(*) FROM animal_transfers WHERE animal_id = ?)
                 + (SELECT COUNT(*) FROM alerts WHERE animal_id = ?)
        """, Integer.class, animalId, animalId, animalId, animalId);

        // The animal no longer counts in a barn, whichever way it leaves the herd.
        var presence = jdbcTemplate.queryForList(
                "SELECT presence_status, current_barn_id FROM presence_states WHERE animal_id = ?", animalId);
        if (!presence.isEmpty()) {
            Object barnId = presence.get(0).get("current_barn_id");
            if ("IN_BARN".equals(presence.get(0).get("presence_status")) && barnId != null) {
                jdbcTemplate.update("UPDATE barns SET current_headcount = GREATEST(0, current_headcount - 1) WHERE barn_id = ?", barnId);
            }
            jdbcTemplate.update("DELETE FROM presence_states WHERE animal_id = ?", animalId);
        }

        if (history == null || history == 0) {
            jdbcTemplate.update("DELETE FROM tag_assignments WHERE animal_id = ?", animalId);
            jdbcTemplate.update("DELETE FROM animals WHERE animal_id = ?", animalId);
            return ResponseEntity.ok(Map.of("status", "DELETED", "animalId", animalId));
        }

        String actor = currentScope.getCurrentScope().username() != null
                ? currentScope.getCurrentScope().username() : "unknown";
        String note = reason != null && !reason.isBlank() ? reason.trim() : "Removed from the herd";
        if (note.length() > 200) {
            note = note.substring(0, 200);
        }
        jdbcTemplate.update("""
            UPDATE animals
            SET lifecycle_status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP, archived_by = ?, archive_reason = ?
            WHERE animal_id = ?
        """, actor, note, animalId);
        jdbcTemplate.update("UPDATE tag_assignments SET valid_to = CURRENT_TIMESTAMP WHERE animal_id = ? AND valid_to IS NULL", animalId);
        jdbcTemplate.update("""
            UPDATE alerts
            SET state = 'RESOLVED', resolved_by = ?, resolved_at = CURRENT_TIMESTAMP,
                resolution_note = 'Animal archived: ' || ?
            WHERE animal_id = ? AND state IN ('OPEN', 'ACKNOWLEDGED')
        """, actor, note, animalId);

        return ResponseEntity.ok(Map.of(
                "status", "ARCHIVED",
                "animalId", animalId,
                "message", "The animal has recorded history, so it was archived instead of deleted. Its history is kept."));
    }

    @GetMapping("/{animalId}/dossier")
    public ResponseEntity<?> downloadAnimalDossier(@PathVariable String animalId) {
        ResponseEntity<?> detailRes = getCattleDetails(animalId);
        if (!detailRes.getStatusCode().is2xxSuccessful()) {
            return detailRes;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dossier_" + animalId.replace(" ", "_") + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(detailRes.getBody());
    }

    private boolean animalExists(String animalId) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM animals WHERE animal_id = ?", Integer.class, animalId);
        return n != null && n > 0;
    }

    private boolean isArchived(String animalId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM animals WHERE animal_id = ? AND lifecycle_status = 'ARCHIVED'", Integer.class, animalId);
        return n != null && n > 0;
    }

    private ResponseEntity<?> archivedConflict(String animalId) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Animal '" + animalId + "' is archived and can no longer be changed."));
    }

    private boolean canAccessAnimal(String animalId) {
        var scope = currentScope.getCurrentScope();
        if (scope.isGlobal()) return true;
        var rows = jdbcTemplate.queryForList("""
            SELECT a.assigned_barn_id, b.farm_id
            FROM animals a
            LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
            WHERE a.animal_id = ?
        """, animalId);
        if (rows.isEmpty()) return false;
        String barnId = (String) rows.get(0).get("assigned_barn_id");
        String farmId = (String) rows.get(0).get("farm_id");
        return scope.canAccessBarn(barnId, farmId);
    }

    private void bindTagToAnimal(String animalId, String epcValue, String credId) {
        jdbcTemplate.update("UPDATE tag_assignments SET valid_to = CURRENT_TIMESTAMP WHERE animal_id = ? AND valid_to IS NULL", animalId);

        List existingTagIds = jdbcTemplate.queryForList("SELECT tag_id FROM rfid_tags WHERE epc_value = ?", String.class, epcValue);
        String tagId;
        if (existingTagIds.isEmpty()) {
            tagId = "TAG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            jdbcTemplate.update("""
                INSERT INTO rfid_tags (tag_id, epc_value, credential_identifier, status)
                VALUES (?, ?, ?, 'ACTIVE')
            """, tagId, epcValue, credId != null && !credId.isBlank() ? credId : "EPC-26");
        } else {
            tagId = String.valueOf(existingTagIds.get(0));
            jdbcTemplate.update("UPDATE rfid_tags SET status = 'ACTIVE' WHERE tag_id = ?", tagId);
            jdbcTemplate.update("UPDATE tag_assignments SET valid_to = CURRENT_TIMESTAMP WHERE tag_id = ? AND valid_to IS NULL", tagId);
        }

        String assignmentId = "ASG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbcTemplate.update("""
            INSERT INTO tag_assignments (assignment_id, animal_id, tag_id, valid_from)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        """, assignmentId, animalId, tagId);
    }
}