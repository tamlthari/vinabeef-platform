package com.vinabeef.movement;

import com.vinabeef.cattle.TagAssignmentRepository;
import com.vinabeef.facilities.BarnRepository;
import com.vinabeef.facilities.Gate;
import com.vinabeef.facilities.GateRepository;
import com.vinabeef.ingestion.IngestionBenchmarkService;
import com.vinabeef.ingestion.RfidReadEvent;
import com.vinabeef.ingestion.RfidReadEventRepository;
import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.ingestion.TelemetryEventProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SafetrustProcessingService implements TelemetryEventProcessor {

    private final StringRedisTemplate redisTemplate;
    private final RfidReadEventRepository readEventRepo;
    private final TagAssignmentRepository tagAssignmentRepo;
    private final GateRepository gateRepo;
    private final MovementRepository movementRepo;
    private final PresenceStateRepository presenceRepo;
    private final BarnRepository barnRepo;
    private final IngestionBenchmarkService benchmarkService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void processSafetrustRead(SafetrustEventDto dto, String ingressMode, long tIngressNano) {
        long eventKey = dto.getEventKey() != null ? dto.getEventKey() : 0L;

        // 1. Hardware Idempotency Check
        if (eventKey != 0L && readEventRepo.existsByEventKey(eventKey)) {
            log.info("[{}] Idempotent duplicate EVENT_KEY {} skipped.", ingressMode, eventKey);
            return;
        }

        // 2. Ignore non-card hardware heartbeats
        if (dto.getCardUuid() == null || dto.getCardUuid().isBlank()) {
            log.info("[{}] Non-card event {} at reader '{}' (Action: {}). Skipping movement.",
                    ingressMode, eventKey, dto.getReaderName(), dto.getGateAction());
            return;
        }

        Instant observedAt = Instant.ofEpochMilli(dto.getTimestamp() != null ? dto.getTimestamp() : System.currentTimeMillis());
        String eventId = UUID.randomUUID().toString();

        // 3. Persist Raw Telemetry Evidence (Zero record loss guarantee)
        RfidReadEvent readEvent = new RfidReadEvent();
        readEvent.setEventId(eventId);
        readEvent.setEventKey(eventKey);
        readEvent.setTransactionId(dto.getTransactionId());
        readEvent.setDeviceKey(dto.getDeviceKey());
        readEvent.setCompanyKey(dto.getCompanyKey());
        readEvent.setReaderName(dto.getReaderName());
        readEvent.setCardUuid(dto.getCardUuid());
        readEvent.setCredentialIdentifier(dto.getCredentialIdentifier());
        readEvent.setUserName(dto.getUserName());
        readEvent.setGateAction(dto.getGateAction());
        readEvent.setHardwareTimestamp(dto.getTimestamp() != null ? dto.getTimestamp() : System.currentTimeMillis());
        readEvent.setObservedAt(observedAt);
        readEvent.setRawPayload(dto.getRawPayload() != null ? dto.getRawPayload() : "{}");
        readEventRepo.save(readEvent);

        // 4. Redis 3-Second Sliding-Window Debounce (prevents double-counting loitering cattle)
        String dedupKey = "dedup:" + dto.getReaderName() + ":" + dto.getCardUuid();
        Boolean isFirstRead = redisTemplate.opsForValue().setIfAbsent(dedupKey, "SEEN", Duration.ofSeconds(3));
        long tDedupDoneNano = System.nanoTime();

        if (Boolean.FALSE.equals(isFirstRead)) {
            benchmarkService.recordMetric(ingressMode, eventKey, tIngressNano, tDedupDoneNano, System.nanoTime(), dto.getTimestamp());
            return;
        }

        // 5. Gate Station Resolution
        Gate gate = gateRepo.findByReaderName(dto.getReaderName()).orElse(null);
        if (gate == null) {
            log.warn("[{}] ANOMALY: Unregistered Gate Reader '{}'", ingressMode, dto.getReaderName());
            raiseUnregisteredReaderAlert(dto.getReaderName(), dto.getCardUuid(), eventKey);
            return;
        }

        // 6. Strict Animal Identity Resolution (Never invent an animal identity)
        String animalId = tagAssignmentRepo.findActiveAnimalByTagEpc(dto.getCardUuid()).orElse(null);
        if (animalId == null && dto.getUserName() != null) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM animals WHERE animal_id = ?", Integer.class, dto.getUserName());
            if (count != null && count > 0) {
                animalId = dto.getUserName();
            }
        }

        if (animalId == null) {
            log.warn("[{}] ANOMALY [UNEXPECTED_TAG]: Unregistered Tag '{}' at Gate '{}' ({})",
                    ingressMode, dto.getCardUuid(), gate.getGateId(), dto.getReaderName());

            raiseUnexpectedTagAlert(
                    gate.getBarnId(),
                    dto.getCardUuid(),
                    dto.getUserName() != null ? dto.getUserName() : "UNKNOWN",
                    dto.getReaderName(),
                    gate.getGateId(),
                    eventKey
            );

            benchmarkService.recordMetric(ingressMode, eventKey, tIngressNano, tDedupDoneNano, System.nanoTime(), dto.getTimestamp());
            return;
        }

        final String resolvedAnimalId = animalId;

        // 7. Direction Resolution (Honors IN_ONLY, OUT_ONLY, or BIDIRECTIONAL gate mode)
        PresenceState state = presenceRepo.findById(resolvedAnimalId).orElseGet(() -> {
            PresenceState s = new PresenceState();
            s.setAnimalId(resolvedAnimalId);
            s.setCurrentBarnId(gate.getBarnId());
            s.setPresenceStatus("OUT_PASTURE");
            return s;
        });

        String mode = gate.getDirectionMode() != null ? gate.getDirectionMode().toUpperCase() : "BIDIRECTIONAL";
        String direction;
        if ("IN_ONLY".equals(mode)) {
            direction = "IN";
        } else if ("OUT_ONLY".equals(mode)) {
            direction = "OUT";
        } else {
            direction = "OUT_PASTURE".equals(state.getPresenceStatus()) ? "IN" : "OUT";
        }
        String nextPresence = "IN".equals(direction) ? "IN_BARN" : "OUT_PASTURE";

        // 8. Check for WRONG_BARN Abnormality (Animal detected at another barn's gate)
        List<String> assignedBarns = jdbcTemplate.queryForList(
                "SELECT assigned_barn_id FROM animals WHERE animal_id = ?", String.class, resolvedAnimalId);
        String assignedBarnId = assignedBarns.isEmpty() ? null : assignedBarns.get(0);

        if (assignedBarnId != null && !assignedBarnId.equals(gate.getBarnId())) {
            log.warn("[{}] ANOMALY [WRONG_BARN]: Animal '{}' (assigned: {}) scanned at Gate '{}' (barn: {})",
                    ingressMode, resolvedAnimalId, assignedBarnId, gate.getGateId(), gate.getBarnId());
            raiseWrongBarnAlert(resolvedAnimalId, assignedBarnId, gate.getBarnId(), dto.getReaderName(), direction);
        }

        // 9. Auto-resolve open MISSING_CATTLE alert when animal returns IN
        if ("IN".equals(direction)) {
            jdbcTemplate.update("""
                UPDATE alerts
                SET state = 'RESOLVED',
                    resolved_by = 'SYSTEM_AUTO',
                    resolved_at = CURRENT_TIMESTAMP,
                    resolution_note = 'Animal detected returning IN via gate ' || ?
                WHERE animal_id = ? AND type = 'MISSING_CATTLE' AND state IN ('OPEN', 'ACKNOWLEDGED')
            """, dto.getReaderName(), resolvedAnimalId);
        }

        // 10. Record Movement Transaction
        String movementId = UUID.randomUUID().toString();
        Movement movement = new Movement();
        movement.setMovementId(movementId);
        movement.setAnimalId(resolvedAnimalId);
        movement.setGateId(gate.getGateId());
        movement.setSourceEventId(eventId);
        movement.setDirection(direction);
        movement.setOccurredAt(observedAt);
        movementRepo.save(movement);

        // 11. Update Presence State & Barn IN / OUT / Headcount Counters
        state.setPresenceStatus(nextPresence);
        state.setCurrentBarnId(gate.getBarnId());
        state.setLastMovementId(movementId);
        state.setUpdatedAt(Instant.now());
        presenceRepo.save(state);

        int delta = "IN".equals(direction) ? 1 : -1;
        barnRepo.adjustHeadcount(gate.getBarnId(), delta);

        if ("IN".equals(direction)) {
            jdbcTemplate.update("UPDATE barns SET daily_in_count = daily_in_count + 1 WHERE barn_id = ?", gate.getBarnId());
        } else {
            jdbcTemplate.update("UPDATE barns SET daily_out_count = daily_out_count + 1 WHERE barn_id = ?", gate.getBarnId());
        }

        long tDbDoneNano = System.nanoTime();
        benchmarkService.recordMetric(ingressMode, eventKey, tIngressNano, tDedupDoneNano, tDbDoneNano, dto.getTimestamp());

        log.info("[{}] Movement recorded: Animal '{}' -> {} at Gate '{}' [Barn: {}] in {} ms",
                ingressMode, resolvedAnimalId, direction, dto.getReaderName(), gate.getBarnId(),
                Math.round((tDbDoneNano - tIngressNano) / 10000.0) / 100.0);
    }

    private void raiseUnexpectedTagAlert(String barnId, String tagEpc, String userName, String readerName, String gateId, long eventKey) {
        jdbcTemplate.update("""
            INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
            VALUES (?, 'UNEXPECTED_TAG', 'HIGH', NULL, ?, 'OPEN',
                    jsonb_build_object('tagEpc', ?, 'userName', ?, 'readerName', ?, 'gateId', ?, 'eventKey', ?),
                    CURRENT_TIMESTAMP)
        """, "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                barnId, tagEpc, userName, readerName, gateId, eventKey);
    }

    private void raiseWrongBarnAlert(String animalId, String assignedBarn, String detectedBarn, String readerName, String direction) {
        jdbcTemplate.update("""
            INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
            VALUES (?, 'WRONG_BARN', 'MEDIUM', ?, ?, 'OPEN',
                    jsonb_build_object('assignedBarn', ?, 'detectedBarn', ?, 'readerName', ?, 'direction', ?),
                    CURRENT_TIMESTAMP)
        """, "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                animalId, detectedBarn, assignedBarn, detectedBarn, readerName, direction);
    }

    private void raiseUnregisteredReaderAlert(String readerName, String cardUuid, long eventKey) {
        jdbcTemplate.update("""
            INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
            VALUES (?, 'UNEXPECTED_TAG', 'MEDIUM', NULL, 'BARN-DL-01', 'OPEN',
                    jsonb_build_object('readerName', ?, 'tagEpc', ?, 'eventKey', ?, 'reason', 'Unregistered gate reader'),
                    CURRENT_TIMESTAMP)
        """, "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                readerName, cardUuid, eventKey);
    }
}