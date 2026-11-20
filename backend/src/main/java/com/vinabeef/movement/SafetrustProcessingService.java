package com.vinabeef.movement;

import com.vinabeef.cattle.TagAssignmentRepository;
import com.vinabeef.facilities.BarnRepository;
import com.vinabeef.facilities.Gate;
import com.vinabeef.facilities.GateRepository;
import com.vinabeef.ingestion.IngestionBenchmarkService;
import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.ingestion.TelemetryEventProcessor;
import com.vinabeef.movement.GateCrossingResolver.Decision;
import com.vinabeef.movement.GateCrossingResolver.Sighting;
import com.vinabeef.movement.GateCrossingStore.ReaderBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SafetrustProcessingService implements TelemetryEventProcessor {

    static final String METHOD_GATE_MODE = "GATE_MODE";
    static final String METHOD_PAIRED_READERS = "PAIRED_READERS";
    /** Unpaired sightings older than this (event time) can no longer find a partner and are dropped. */
    private static final long UNPAIRED_RETENTION_MS = 3_600_000L;

    private final StringRedisTemplate redisTemplate;
    private final TagAssignmentRepository tagAssignmentRepo;
    private final GateRepository gateRepo;
    private final MovementRepository movementRepo;
    private final PresenceStateRepository presenceRepo;
    private final BarnRepository barnRepo;
    private final IngestionBenchmarkService benchmarkService;
    private final JdbcTemplate jdbcTemplate;
    private final GateCrossingStore crossingStore;

    @Override
    @Transactional
    public void processSafetrustRead(SafetrustEventDto dto, String ingressMode, long tIngressNano) {
        long metricKey = dto.getEventKey() != null ? dto.getEventKey() : 0L;
        long hardwareMs = dto.getTimestamp() != null ? dto.getTimestamp() : System.currentTimeMillis();
        String eventClass = dto.getEventClass();
        String eventId = UUID.randomUUID().toString();

        // 1. Keep every event exactly once. EVENT_KEY is the idempotency key, so a replay of the same event
        //    (edge outbox, second subscriber, Safetrust redelivery) is a no-op instead of an error.
        if (!storeRawEvent(eventId, dto, eventClass, hardwareMs, ingressMode)) {
            log.info("[{}] Idempotent duplicate EVENT_KEY {} skipped.", ingressMode, metricKey);
            return;
        }

        // 2. Reader connect/disconnect, LiDAR, RF info and temperature reports: stored above, no movement.
        if (!"CARD_READ".equals(eventClass)) {
            recordReaderEvent(dto, hardwareMs, ingressMode);
            return;
        }

        processCardRead(dto, ingressMode, tIngressNano, metricKey, hardwareMs, eventId);
    }

    private void processCardRead(SafetrustEventDto dto, String ingressMode, long tIngressNano,
                                 long metricKey, long hardwareMs, String eventId) {
        String card = dto.getCardUuid();
        String readerName = dto.getReaderName();
        Instant observedAt = Instant.ofEpochMilli(hardwareMs);

        // 3. Gate resolution: a reader listed in gate_readers is one side of a paired gate; a reader that is
        //    only named on the gate is a single-reader gate that uses the gate's direction_mode.
        Optional<ReaderBinding> binding = crossingStore.findReader(readerName);
        crossingStore.touchReader(readerName, hardwareMs);

        Gate gate = binding
                .flatMap(b -> gateRepo.findById(b.gateId()))
                .orElseGet(() -> readerName == null ? null : gateRepo.findByReaderName(readerName).orElse(null));
        if (gate == null) {
            log.warn("[{}] ANOMALY: Unregistered Gate Reader '{}'", ingressMode, readerName);
            raiseUnregisteredReaderAlert(readerName, card, metricKey);
            return;
        }

        // 4. Strict animal identity resolution (never invent an animal identity)
        String animalId = tagAssignmentRepo.findActiveAnimalByTagEpc(card).orElse(null);
        if (animalId == null && dto.getUserName() != null) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM animals WHERE animal_id = ?", Integer.class, dto.getUserName());
            if (count != null && count > 0) {
                animalId = dto.getUserName();
            }
        }

        if (animalId == null) {
            log.warn("[{}] ANOMALY [UNEXPECTED_TAG]: Unregistered Tag '{}' at Gate '{}' ({})",
                    ingressMode, card, gate.getGateId(), readerName);
            raiseUnexpectedTagAlert(gate.getBarnId(), card,
                    dto.getUserName() != null ? dto.getUserName() : "UNKNOWN",
                    readerName, gate.getGateId(), metricKey);
            benchmarkService.recordMetric(ingressMode, metricKey, tIngressNano, System.nanoTime(), System.nanoTime(), hardwareMs);
            return;
        }

        final String resolvedAnimalId = animalId;
        PresenceState state = presenceRepo.findById(resolvedAnimalId).orElseGet(() -> {
            PresenceState s = new PresenceState();
            s.setAnimalId(resolvedAnimalId);
            s.setCurrentBarnId(gate.getBarnId());
            s.setPresenceStatus("OUT_PASTURE");
            return s;
        });
        String previousStatus = state.getPresenceStatus();
        String previousBarnId = state.getCurrentBarnId();

        // 5. Direction
        String direction;
        String method;
        String sourceEventId = eventId;
        String firstEventId = null;
        Instant occurredAt = observedAt;
        Instant crossingStartedAt = null;

        if (binding.isPresent() && binding.get().side() != null) {
            // 5a. Paired readers: direction is the ORDER in which the two readers saw the tag (event time).
            method = METHOD_PAIRED_READERS;
            String gateId = gate.getGateId();
            long window = gate.getPairWindowMs();
            long debounce = gate.getRepeatDebounceMs();
            long reach = Math.max(window, debounce);

            crossingStore.lockCrossing(card, gateId);
            Sighting incoming = new Sighting(binding.get().side(), hardwareMs, eventId);
            Decision decision = GateCrossingResolver.decide(
                    crossingStore.findUnpaired(card, gateId, hardwareMs - reach, hardwareMs + reach),
                    incoming,
                    crossingStore.crossingCompletedNear(resolvedAnimalId, gateId, hardwareMs, debounce),
                    window, debounce);

            if (decision.action() != GateCrossingResolver.Action.COMPLETE) {
                if (decision.action() == GateCrossingResolver.Action.REMEMBER) {
                    crossingStore.addUnpaired(card, gateId, incoming);
                    crossingStore.pruneBefore(card, gateId, hardwareMs - UNPAIRED_RETENTION_MS);
                }
                log.info("[{}] Half crossing: tag '{}' seen at {} reader '{}' ({}). Waiting for the other reader.",
                        ingressMode, card, binding.get().side(), readerName, decision.action());
                benchmarkService.recordMetric(ingressMode, metricKey, tIngressNano, System.nanoTime(), System.nanoTime(), hardwareMs);
                return;
            }

            crossingStore.consume(card, gateId, decision.partner().eventId(), decision.later().eventMs(), window);
            direction = decision.direction();
            sourceEventId = decision.later().eventId();
            firstEventId = decision.first().eventId();
            occurredAt = Instant.ofEpochMilli(decision.later().eventMs());
            crossingStartedAt = Instant.ofEpochMilli(decision.first().eventMs());
        } else {
            // 5b. Single reader gate: honour IN_ONLY, OUT_ONLY or BIDIRECTIONAL (toggle by presence).
            method = METHOD_GATE_MODE;
            if (isRepeatRead(card, readerName)) {
                benchmarkService.recordMetric(ingressMode, metricKey, tIngressNano, System.nanoTime(), System.nanoTime(), hardwareMs);
                return;
            }
            String mode = gate.getDirectionMode() != null ? gate.getDirectionMode().toUpperCase() : "BIDIRECTIONAL";
            if ("IN_ONLY".equals(mode)) {
                direction = "IN";
            } else if ("OUT_ONLY".equals(mode)) {
                direction = "OUT";
            } else {
                direction = "OUT_PASTURE".equals(previousStatus) ? "IN" : "OUT";
            }
        }
        long tDedupDoneNano = System.nanoTime();
        String nextPresence = "IN".equals(direction) ? "IN_BARN" : "OUT_PASTURE";

        // 6. WRONG_BARN: the animal is assigned to another barn
        List<String> assignedBarns = jdbcTemplate.queryForList(
                "SELECT assigned_barn_id FROM animals WHERE animal_id = ?", String.class, resolvedAnimalId);
        String assignedBarnId = assignedBarns.isEmpty() ? null : assignedBarns.get(0);
        if (assignedBarnId != null && !assignedBarnId.equals(gate.getBarnId())) {
            log.warn("[{}] ANOMALY [WRONG_BARN]: Animal '{}' (assigned: {}) scanned at Gate '{}' (barn: {})",
                    ingressMode, resolvedAnimalId, assignedBarnId, gate.getGateId(), gate.getBarnId());
            raiseWrongBarnAlert(resolvedAnimalId, assignedBarnId, gate.getBarnId(), readerName, direction);
        }

        // 7. An animal that comes back IN resolves its open MISSING_CATTLE alert
        if ("IN".equals(direction)) {
            jdbcTemplate.update("""
                UPDATE alerts
                SET state = 'RESOLVED',
                    resolved_by = 'SYSTEM_AUTO',
                    resolved_at = CURRENT_TIMESTAMP,
                    resolution_note = 'Animal detected returning IN via gate ' || ?
                WHERE animal_id = ? AND type = 'MISSING_CATTLE' AND state IN ('OPEN', 'ACKNOWLEDGED')
            """, readerName, resolvedAnimalId);
        }

        // 8. Movement
        String movementId = UUID.randomUUID().toString();
        Movement movement = new Movement();
        movement.setMovementId(movementId);
        movement.setAnimalId(resolvedAnimalId);
        movement.setGateId(gate.getGateId());
        movement.setSourceEventId(sourceEventId);
        movement.setDirection(direction);
        movement.setOccurredAt(occurredAt);
        movement.setDetectionMethod(method);
        movement.setFirstEventId(firstEventId);
        movement.setCrossingStartedAt(crossingStartedAt);
        movementRepo.save(movement);

        // 9. Presence and barn counters
        state.setPresenceStatus(nextPresence);
        state.setCurrentBarnId(gate.getBarnId());
        state.setLastMovementId(movementId);
        state.setUpdatedAt(Instant.now());
        presenceRepo.save(state);

        int delta = "IN".equals(direction) ? 1 : -1;
        if (METHOD_PAIRED_READERS.equals(method)) {
            // The direction is measured, not derived from the previous state, so the state can disagree
            // (a missed crossing earlier). Only count the headcount when the state really changes.
            boolean changed = !nextPresence.equals(previousStatus) || !gate.getBarnId().equals(previousBarnId);
            if (!changed) {
                delta = 0;
                log.warn("[{}] Animal '{}' crossed {} but was already {} in barn {}. Movement recorded, headcount unchanged.",
                        ingressMode, resolvedAnimalId, direction, previousStatus, previousBarnId);
            }
        }
        if (delta != 0) {
            barnRepo.adjustHeadcount(gate.getBarnId(), delta);
        }
        if ("IN".equals(direction)) {
            jdbcTemplate.update("UPDATE barns SET daily_in_count = daily_in_count + 1 WHERE barn_id = ?", gate.getBarnId());
        } else {
            jdbcTemplate.update("UPDATE barns SET daily_out_count = daily_out_count + 1 WHERE barn_id = ?", gate.getBarnId());
        }

        long tDbDoneNano = System.nanoTime();
        benchmarkService.recordMetric(ingressMode, metricKey, tIngressNano, tDedupDoneNano, tDbDoneNano, hardwareMs);
        log.info("[{}] Movement recorded: Animal '{}' -> {} at Gate '{}' via {} [Barn: {}] in {} ms",
                ingressMode, resolvedAnimalId, direction, gate.getGateId(), method, gate.getBarnId(),
                Math.round((tDbDoneNano - tIngressNano) / 10000.0) / 100.0);
    }

    /**
     * Single reader gates only: a tag held in front of the reader reports several times, so reads within 3 s
     * count once. Redis is an optimisation here, not a dependency: if it is unreachable the read is counted
     * instead of the whole event failing.
     */
    private boolean isRepeatRead(String card, String readerName) {
        try {
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent("dedup:" + readerName + ":" + card, "SEEN", Duration.ofSeconds(3));
            return Boolean.FALSE.equals(first);
        } catch (RuntimeException ex) {
            log.warn("Redis debounce unavailable ({}); counting the read.", ex.getMessage());
            return false;
        }
    }

    private void recordReaderEvent(SafetrustEventDto dto, long hardwareMs, String ingressMode) {
        String action = dto.getRawAction();
        String readerName = dto.getReaderName();
        crossingStore.touchReader(readerName, hardwareMs);
        if ("READER_CONNECT_MQTT".equals(action)) {
            crossingStore.setConnection(readerName, "ONLINE", dto.getIpAddress(), hardwareMs);
        } else if ("READER_DISCONNECT_MQTT".equals(action)) {
            crossingStore.setConnection(readerName, "OFFLINE", null, hardwareMs);
        }
        log.info("[{}] {} event {} at reader '{}' (action: {}) stored.",
                ingressMode, dto.getEventClass(), dto.getEventKey(), readerName, dto.getGateAction());
    }

    /** Inserts the raw event. Returns false when an event with the same EVENT_KEY is already stored. */
    private boolean storeRawEvent(String eventId, SafetrustEventDto dto, String eventClass,
                                  long hardwareMs, String ingressMode) {
        String payload = dto.getRawPayload() != null && !dto.getRawPayload().isBlank() ? dto.getRawPayload() : "{}";
        String readerName = dto.getReaderName() != null ? dto.getReaderName() : "UNKNOWN";
        int rows = jdbcTemplate.update("""
                INSERT INTO rfid_read_events (
                    event_id, event_key, transaction_id, device_key, company_key, reader_name, card_uuid,
                    credential_identifier, user_name, gate_action, hardware_timestamp, observed_at, raw_payload,
                    ingress_source, event_class, reader_key, user_key, building_access_key, credential_type,
                    sensor_type, cloud_modified_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_key) DO NOTHING
                """,
                eventId, dto.getEventKey(), dto.getTransactionId(), dto.getDeviceKey(), dto.getCompanyKey(),
                readerName, dto.getCardUuid(), dto.getCredentialIdentifier(), dto.getUserName(),
                dto.getGateAction(), hardwareMs, Timestamp.from(Instant.ofEpochMilli(hardwareMs)), payload,
                ingressMode, eventClass, dto.getReaderKey(), dto.getUserKey(), dto.getBuildingAccessKey(),
                dto.getCredentialType(), dto.getSensorType(), dto.getModified());
        return rows == 1;
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
