package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.facilities.Barn;
import com.vinabeef.facilities.BarnRepository;
import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.ingestion.TelemetryEventProcessor;
import com.vinabeef.security.BarnAccessPolicy;
import com.vinabeef.security.CurrentScopeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@SuppressWarnings("unchecked")
public class GateMonitorController {

    private final BarnRepository barnRepo;
    private final JdbcTemplate jdbcTemplate;
    private final TelemetryEventProcessor eventProcessor;
    private final CurrentScopeProvider currentScope;
    private final BarnAccessPolicy barnAccess;
    private final ReportClock clock;

    @GetMapping("/barns")
    public ResponseEntity<List<Barn>> getAllBarns() {
        var scope = currentScope.getCurrentScope();
        List<Barn> all = barnRepo.findAll();
        if (scope.isGlobal()) {
            return ResponseEntity.ok(all);
        }
        if (scope.isFarmScoped()) {
            return ResponseEntity.ok(all.stream()
                    .filter(b -> Objects.equals(scope.farmId(), b.getFarmId()))
                    .toList());
        }
        if (scope.isBarnScoped()) {
            return ResponseEntity.ok(all.stream()
                    .filter(b -> scope.barnIds().contains(b.getBarnId()))
                    .toList());
        }
        return ResponseEntity.ok(List.of());
    }

    /**
     * Newest gate passages for the dashboard stream. The fields of the old response (movementId, animalId, gateId, direction,
     * detectionMethod, occurredAt) are unchanged; gateLabel, gateReader, readerName (the reader that completed the pass),
     * firstReader and barnCode are added so the screen can show names people recognise instead of the internal gate key.
     */
    @GetMapping("/movements/recent")
    public ResponseEntity<List<Map<String, Object>>> getRecentMovements() {
        var scope = currentReportScope();
        if (scope.seesNothing()) {
            return ResponseEntity.ok(List.of());
        }
        var q = ListQueries.recentMovements(scope, 15);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(q.sql(), q.params().toArray());
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("movementId", r.get("movement_id"));
            m.put("animalId", r.get("animal_id"));
            m.put("gateId", r.get("gate_id"));
            m.put("direction", r.get("direction"));
            m.put("detectionMethod", r.get("detection_method"));
            m.put("occurredAt", r.get("occurred_at") instanceof Timestamp t ? t.toInstant().toString() : r.get("occurred_at"));
            m.put("gateLabel", r.get("gate_label"));
            m.put("gateReader", r.get("gate_reader"));
            m.put("readerName", r.get("read_reader"));
            m.put("firstReader", r.get("first_reader"));
            m.put("barnCode", r.get("barn_code"));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    private ReportQueries.Scope currentReportScope() {
        var s = currentScope.getCurrentScope();
        if (s.isGlobal()) {
            return ReportQueries.Scope.ALL;
        }
        if (s.isFarmScoped()) {
            return ReportQueries.Scope.farm(s.farmId());
        }
        if (s.isBarnScoped()) {
            return ReportQueries.Scope.barns(s.barnIds());
        }
        return ReportQueries.Scope.NONE;
    }

    @PostMapping("/gates/simulate-read")
    public ResponseEntity<?> simulateGateRead(@RequestBody SafetrustEventDto dto) {
        long tStart = System.nanoTime();
        if (dto.getTimestamp() == null) {
            dto.setTimestamp(System.currentTimeMillis());
        }
        eventProcessor.processSafetrustRead(dto, "GATE_REST", tStart);
        double latencyMs = Math.round((System.nanoTime() - tStart) / 10000.0) / 100.0;
        return ResponseEntity.ok(Map.of(
                "status", "PROCESSED",
                "readerName", String.valueOf(dto.getReaderName()),
                "cardUuid", String.valueOf(dto.getCardUuid()),
                "latencyMs", latencyMs
        ));
    }

    @PostMapping("/barns/{barnId}/verify-return")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR')")
    @Audited(action = "VERIFY_BARN_RETURN_COUNT", targetType = "BARN")
    @Transactional
    public ResponseEntity<?> verifyBarnReturnCount(@PathVariable String barnId) {
        var scope = currentScope.getCurrentScope();
        if (!barnAccess.canAccessBarnId(scope, barnId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access denied: Barn " + barnId + " is outside your assigned scope."));
        }

        var barnRows = jdbcTemplate.queryForList("""
            SELECT b.barn_id, b.code, c.in_barn_count, c.out_pasture_count,
                   COALESCE(m.in_count, 0)  AS passes_in,
                   COALESCE(m.out_count, 0) AS passes_out
            FROM barns b
            JOIN v_barn_counts c ON c.barn_id = b.barn_id
            LEFT JOIN (
                SELECT g.barn_id,
                       COUNT(*) FILTER (WHERE mv.direction = 'IN')  AS in_count,
                       COUNT(*) FILTER (WHERE mv.direction = 'OUT') AS out_count
                FROM movements mv
                JOIN gates g ON g.gate_id = mv.gate_id
                WHERE g.barn_id = ? AND mv.occurred_at >= ? AND mv.occurred_at < ?
                GROUP BY g.barn_id
            ) m ON m.barn_id = b.barn_id
            WHERE b.barn_id = ?
        """, barnId, Timestamp.from(clock.today().fromInclusive()), Timestamp.from(clock.today().toExclusive()), barnId);

        if (barnRows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map row = barnRows.get(0);
        // Today's passes through this barn's gates (local day), and the cattle that are out right now.
        // The old counters never reset, so "exited" and "returned" were lifetime totals.
        int inCount = ((Number) row.get("passes_in")).intValue();
        int outCount = ((Number) row.get("passes_out")).intValue();
        int deficit = ((Number) row.get("out_pasture_count")).intValue();

        List<String> unreturnedAnimals = jdbcTemplate.queryForList("""
            SELECT a.animal_id
            FROM animals a
            JOIN presence_states ps ON a.animal_id = ps.animal_id
            WHERE ps.current_barn_id = ?
              AND a.lifecycle_status = 'ACTIVE'
              AND ps.presence_status = 'OUT_PASTURE'
              AND NOT EXISTS (
                  SELECT 1 FROM alerts al
                  WHERE al.animal_id = a.animal_id
                    AND al.type = 'MISSING_CATTLE'
                    AND al.state IN ('OPEN', 'ACKNOWLEDGED')
              )
            ORDER BY a.animal_id ASC
            LIMIT 50
        """, String.class, barnId);

        for (String animalId : unreturnedAnimals) {
            String alertId = "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            jdbcTemplate.update("""
                INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
                VALUES (?, 'MISSING_CATTLE', 'HIGH', ?, ?, 'OPEN',
                        jsonb_build_object('reason', 'Cattle that left this barn have not come back', 'exitedToday', ?, 'returnedToday', ?, 'stillOut', ?),
                        CURRENT_TIMESTAMP)
            """, alertId, animalId, barnId, outCount, inCount, deficit);
        }

        return ResponseEntity.ok(Map.of(
                "barnId", barnId,
                "exitedCount", outCount,
                "returnedCount", inCount,
                "deficit", deficit,   // cattle whose last gate pass at this barn was OUT
                "newlyFlaggedAlerts", unreturnedAnimals.size()
        ));
    }

    @GetMapping("/facilities/distribution")
    public ResponseEntity<?> getFacilityDistribution() {
        var scope = currentScope.getCurrentScope();

        var today = clock.today();
        String sql = """
            SELECT
                f.farm_id,
                f.name AS farm_name,
                f.location,
                b.barn_id,
                b.code AS barn_code,
                b.capacity AS barn_capacity,
                c.in_barn_count AS barn_headcount,
                c.out_pasture_count AS barn_out_pasture,
                c.unseen_count AS barn_unseen,
                COALESCE(m.in_count, 0)  AS barn_in_count,
                COALESCE(m.out_count, 0) AS barn_out_count,
                c.assigned_count AS barn_assigned_animals,
                COALESCE(stats.vaccinated_animals, 0) AS barn_vaccinated_animals
            FROM farms f
            JOIN barns b ON f.farm_id = b.farm_id
            JOIN v_barn_counts c ON c.barn_id = b.barn_id
            LEFT JOIN (
                SELECT g.barn_id,
                       COUNT(*) FILTER (WHERE mv.direction = 'IN')  AS in_count,
                       COUNT(*) FILTER (WHERE mv.direction = 'OUT') AS out_count
                FROM movements mv
                JOIN gates g ON g.gate_id = mv.gate_id
                WHERE mv.occurred_at >= ? AND mv.occurred_at < ?
                GROUP BY g.barn_id
            ) m ON m.barn_id = b.barn_id
            LEFT JOIN (
                SELECT a.assigned_barn_id, COUNT(*) AS vaccinated_animals
                FROM animals a
                WHERE a.lifecycle_status = 'ACTIVE'
                  AND EXISTS (SELECT 1 FROM health_records hr
                              WHERE hr.animal_id = a.animal_id AND hr.record_type = 'VACCINATION')
                GROUP BY a.assigned_barn_id
            ) stats ON b.barn_id = stats.assigned_barn_id
            ORDER BY f.farm_id, c.assigned_count DESC, b.code ASC
        """;

        var rows = jdbcTemplate.queryForList(sql, Timestamp.from(today.fromInclusive()), Timestamp.from(today.toExclusive()));
        var farmMap = new LinkedHashMap<String, Object>();

        for (var row : rows) {
            String farmId = (String) row.get("farm_id");
            String barnId = (String) row.get("barn_id");

            // Enforce Farm Manager & Multi-Barn Supervisor visibility boundaries
            if (!scope.canAccessBarn(barnId, farmId)) {
                continue;
            }

            Map<String, Object> farmData = (Map<String, Object>) farmMap.computeIfAbsent(farmId, k -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("farmId", farmId);
                map.put("farmName", row.get("farm_name"));
                map.put("location", row.get("location"));
                map.put("totalCapacity", 0);
                map.put("totalHeadcount", 0);
                map.put("totalInCount", 0);
                map.put("totalOutCount", 0);
                map.put("totalOutPasture", 0);
                map.put("totalNotSeen", 0);
                map.put("totalAnimals", 0);
                map.put("vaccinatedAnimals", 0);
                map.put("barns", new ArrayList<Object>());
                return map;
            });

            int capacity = ((Number) row.get("barn_capacity")).intValue();
            int headcount = ((Number) row.get("barn_headcount")).intValue();
            int inCount = ((Number) row.get("barn_in_count")).intValue();
            int outCount = ((Number) row.get("barn_out_count")).intValue();
            int outPasture = ((Number) row.get("barn_out_pasture")).intValue();
            int notSeen = ((Number) row.get("barn_unseen")).intValue();
            int assignedAnimals = ((Number) row.get("barn_assigned_animals")).intValue();
            int vaccinatedAnimals = ((Number) row.get("barn_vaccinated_animals")).intValue();

            farmData.put("totalCapacity", (int) farmData.get("totalCapacity") + capacity);
            farmData.put("totalHeadcount", (int) farmData.get("totalHeadcount") + headcount);
            farmData.put("totalInCount", (int) farmData.get("totalInCount") + inCount);
            farmData.put("totalOutCount", (int) farmData.get("totalOutCount") + outCount);
            farmData.put("totalOutPasture", (int) farmData.get("totalOutPasture") + outPasture);
            farmData.put("totalNotSeen", (int) farmData.get("totalNotSeen") + notSeen);
            farmData.put("totalAnimals", (int) farmData.get("totalAnimals") + assignedAnimals);
            farmData.put("vaccinatedAnimals", (int) farmData.get("vaccinatedAnimals") + vaccinatedAnimals);

            Map<String, Object> barnInfo = new LinkedHashMap<>();
            barnInfo.put("barnId", barnId);
            barnInfo.put("code", row.get("barn_code"));
            barnInfo.put("capacity", capacity);
            barnInfo.put("currentHeadcount", headcount);
            barnInfo.put("inCount", inCount);
            barnInfo.put("outCount", outCount);
            barnInfo.put("outPasture", outPasture);
            barnInfo.put("notSeen", notSeen);
            // cattle that left through this barn's gate and have not been seen coming back
            barnInfo.put("unreturnedDeficit", outPasture);
            barnInfo.put("assignedAnimals", assignedAnimals);
            barnInfo.put("vaccinatedAnimals", vaccinatedAnimals);

            double barnUtil = capacity > 0 ? (headcount * 100.0) / capacity : 0.0;
            double barnVacRate = assignedAnimals > 0 ? (vaccinatedAnimals * 100.0) / assignedAnimals : 0.0;
            barnInfo.put("utilizationRate", Math.round(barnUtil * 10.0) / 10.0);
            barnInfo.put("vaccinationRate", Math.round(barnVacRate * 10.0) / 10.0);

            List<Object> barnList = (List<Object>) farmData.get("barns");
            barnList.add(barnInfo);
        }

        for (Object val : farmMap.values()) {
            Map<String, Object> farmData = (Map<String, Object>) val;
            int totCap = (int) farmData.get("totalCapacity");
            int totHead = (int) farmData.get("totalHeadcount");
            int totAnim = (int) farmData.get("totalAnimals");
            int totVac = (int) farmData.get("vaccinatedAnimals");

            double utilRate = totCap > 0 ? (totHead * 100.0) / totCap : 0.0;
            double vacRate = totAnim > 0 ? (totVac * 100.0) / totAnim : 0.0;
            farmData.put("utilizationRate", Math.round(utilRate * 10.0) / 10.0);
            farmData.put("vaccinationRate", Math.round(vacRate * 10.0) / 10.0);

            List<Object> barnList = (List<Object>) farmData.get("barns");
            barnList.sort((o1, o2) -> {
                Map<String, Object> a = (Map<String, Object>) o1;
                Map<String, Object> b = (Map<String, Object>) o2;

                int headCmp = Integer.compare(
                        ((Number) b.get("assignedAnimals")).intValue(),
                        ((Number) a.get("assignedAnimals")).intValue()
                );
                if (headCmp != 0) return headCmp;

                int vacCmp = Double.compare(
                        ((Number) b.get("vaccinationRate")).doubleValue(),
                        ((Number) a.get("vaccinationRate")).doubleValue()
                );
                if (vacCmp != 0) return vacCmp;

                return String.valueOf(a.get("code")).compareTo(String.valueOf(b.get("code")));
            });
        }

        return ResponseEntity.ok(new ArrayList<>(farmMap.values()));
    }
}