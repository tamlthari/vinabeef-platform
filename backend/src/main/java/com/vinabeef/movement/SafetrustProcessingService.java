package com.vinabeef.movement;

import com.vinabeef.facilities.Gate;
import com.vinabeef.facilities.GateDirectionMode;
import com.vinabeef.ingestion.IngestionBenchmarkService;
import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.ingestion.TelemetryEventProcessor;
import com.vinabeef.movement.GateCrossingResolver.Decision;
import com.vinabeef.movement.GateCrossingResolver.Sighting;
import com.vinabeef.movement.port.AlertSink;
import com.vinabeef.movement.port.AnimalRegistry;
import com.vinabeef.movement.port.BarnCounters;
import com.vinabeef.movement.port.GateCrossingStore;
import com.vinabeef.movement.port.GateCrossingStore.ReaderBinding;
import com.vinabeef.movement.port.GateLookup;
import com.vinabeef.movement.port.MovementBook;
import com.vinabeef.movement.port.ReadEventLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The movement use case: turns one reader event into a stored raw read and, for a card read, into a movement. It
 * knows the rules (which reader pairs with which, which direction, when a read is a repeat, which alert) and nothing
 * about how anything is stored: every read and write goes through a port in {@code movement.port}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SafetrustProcessingService implements TelemetryEventProcessor {

    /** Unpaired sightings older than this (event time) can no longer find a partner and are dropped. */
    private static final long UNPAIRED_RETENTION_MS = 3_600_000L;

    private final AnimalRegistry animals;
    private final GateLookup gates;
    private final MovementBook book;
    private final BarnCounters barns;
    private final AlertSink alerts;
    private final ReadEventLog eventLog;
    private final GateCrossingStore crossingStore;
    private final IngestionBenchmarkService benchmarkService;
    private final CredentialCardResolver credentialResolver;
    private final CrossSourceMatcher crossSourceMatcher;

    @Override
    @Transactional
    public void processSafetrustRead(SafetrustEventDto dto, String ingressMode, long tIngressNano) {
        long metricKey = dto.getEventKey() != null ? dto.getEventKey() : 0L;
        long hardwareMs = dto.getTimestamp() != null ? dto.getTimestamp() : System.currentTimeMillis();
        String eventId = UUID.randomUUID().toString();

        // 0. A USB reader on the barn gateway sends the credential number (for example 38), not the card UID.
        //    Find the card first; a number that maps to no card is kept and shown, never dropped, never guessed.
        String resolvedBy = null;
        if (dto.isLocalUsb() && dto.getCardUuid() == null && dto.getCredentialNumber() != null) {
            Optional<CredentialCardResolver.Resolution> found = credentialResolver.resolve(dto.getCredentialNumber());
            if (found.isPresent()) {
                dto.setCardUuid(found.get().cardUuid());
                resolvedBy = found.get().resolvedBy();
            }
        }
        String eventClass = dto.getEventClass();

        // 1. Keep every event exactly once. EVENT_KEY is the idempotency key, so a replay of the same event
        //    (edge outbox, second subscriber, Safetrust redelivery) is a no-op instead of an error.
        if (!eventLog.storeIfNew(eventId, dto, eventClass, hardwareMs, ingressMode, resolvedBy)) {
            log.info("[{}] Idempotent duplicate EVENT_KEY {} skipped.", ingressMode, metricKey);
            return;
        }

        if (SafetrustEventDto.CLASS_LOCAL_UNMAPPED.equals(eventClass)) {
            handleUnmappedLocalRead(dto, ingressMode, metricKey);
            return;
        }

        // 2. Reader connect/disconnect, LiDAR, RF info and temperature reports: stored above, no movement.
        if (!"CARD_READ".equals(eventClass)) {
            if (!dto.isLocalUsb()) {
                recordReaderEvent(dto, hardwareMs, ingressMode);
            }
            return;
        }

        processCardRead(dto, ingressMode, tIngressNano, metricKey, hardwareMs, eventId);
    }

    /**
     * Retries one stored USB read whose credential number mapped to no card. When the number is known now, the read becomes an
     * ordinary card read and is processed from the time it was taken (the gate logic copes with late reads).
     *
     * @return true when the read was resolved and processed
     */
    @Override
    @Transactional
    public boolean reprocessUnmappedLocalRead(String eventId) {
        Optional<ReadEventLog.UnmappedRead> stored = eventLog.findUnmapped(eventId);
        if (stored.isEmpty()) {
            return false;
        }
        ReadEventLog.UnmappedRead row = stored.get();
        Optional<CredentialCardResolver.Resolution> found = credentialResolver.resolve(row.credential());
        if (found.isEmpty()) {
            return false;
        }
        eventLog.markResolved(eventId, found.get().cardUuid(), found.get().resolvedBy());

        SafetrustEventDto dto = new SafetrustEventDto();
        dto.setEventKey(row.eventKey());
        dto.setReaderName(row.readerName());
        dto.setCredentialIdentifier(row.credential());
        dto.setCardUuid(found.get().cardUuid());
        dto.setTimestamp(row.hardwareMs());
        dto.setEdgeSource(SafetrustEventDto.SOURCE_LOCAL_USB);
        dto.setEdgeDevice(row.sourceDevice());
        processCardRead(dto, "REPROCESS", System.nanoTime(), dto.getEventKey() != null ? dto.getEventKey() : 0L, row.hardwareMs(), eventId);
        return true;
    }

    @Override
    public List<String> unmappedLocalReadIds(int limit) {
        return eventLog.unmappedIds(limit);
    }

    @Override
    public int countUnmappedLocalReads() {
        return eventLog.countUnmapped();
    }

    /** A USB read whose credential number is not known: stored (already), shown on the Raw reads page, and one alert for the barn. */
    private void handleUnmappedLocalRead(SafetrustEventDto dto, String ingressMode, long metricKey) {
        String readerName = dto.getReaderName();
        Optional<Gate> gate = gateForReader(readerName);
        log.warn("[{}] ANOMALY: credential {} from USB reader '{}' maps to no card yet; the read is kept as LOCAL_UNMAPPED.",
                ingressMode, dto.getCredentialNumber(), readerName);
        if (gate.isPresent()) {
            alerts.raiseUnmappedCredential(gate.get().getBarnId(), dto.getCredentialNumber(), readerName,
                    gate.get().getGateId(), metricKey);
        } else {
            log.warn("[{}] No gate is registered for reader '{}': no alert raised for the unmapped credential.", ingressMode, readerName);
        }
    }

    private Optional<Gate> gateForReader(String readerName) {
        if (readerName == null) {
            return Optional.empty();
        }
        Optional<Gate> viaPair = crossingStore.findReader(readerName).flatMap(b -> gates.byId(b.gateId()));
        Optional<Gate> gate = viaPair.isPresent() ? viaPair : gates.activeByReaderName(readerName);
        return gate.filter(g -> g.getRetiredAt() == null);
    }

    private void processCardRead(SafetrustEventDto dto, String ingressMode, long tIngressNano,
                                 long metricKey, long hardwareMs, String eventId) {
        String card = dto.getCardUuid();
        String readerName = dto.getReaderName();
        Instant observedAt = Instant.ofEpochMilli(hardwareMs);

        // 3. Gate resolution: a reader listed in gate_readers is one side of a paired gate; a reader that is
        //    only named on the gate is a single-reader gate that uses the gate's direction_mode.
        Optional<ReaderBinding> binding = crossingStore.findReader(readerName);
        // "last seen" belongs to the reader's own events: a USB read on the gateway says nothing about the wall reader
        if (!dto.isLocalUsb()) {
            crossingStore.touchReader(readerName, hardwareMs);
        }

        // 2b. The same tap may arrive by both routes (Safetrust cloud and the gateway's USB reader). The first copy counts.
        Optional<String> firstCopy = crossSourceMatcher.claimFirstCopy(eventId, readerName, card, dto.getReadSource(), hardwareMs);
        if (firstCopy.isPresent()) {
            benchmarkService.recordMetric(ingressMode, metricKey, tIngressNano, System.nanoTime(), System.nanoTime(), hardwareMs);
            return;
        }

        Gate found = binding
                .flatMap(b -> gates.byId(b.gateId()))
                .orElseGet(() -> readerName == null ? null : gates.activeByReaderName(readerName).orElse(null));
        // a retired reader is no longer part of the farm: its reads raise the "unregistered reader" alert
        final Gate gate = found != null && found.getRetiredAt() == null ? found : null;
        if (gate == null) {
            log.warn("[{}] ANOMALY: Unregistered Gate Reader '{}'", ingressMode, readerName);
            alerts.raiseUnregisteredReader(readerName, card, metricKey);
            return;
        }

        // 4. Strict animal identity resolution (never invent an animal identity)
        String animalId = animals.activeAnimalForTag(card).orElse(null);
        if (animalId == null && dto.getUserName() != null && animals.isLiveAnimal(dto.getUserName())) {
            animalId = dto.getUserName();
        }

        if (animalId == null) {
            log.warn("[{}] ANOMALY [UNEXPECTED_TAG]: Unregistered Tag '{}' at Gate '{}' ({})",
                    ingressMode, card, gate.getGateId(), readerName);
            alerts.raiseUnexpectedTag(gate.getBarnId(), card,
                    dto.getUserName() != null ? dto.getUserName() : "UNKNOWN",
                    readerName, gate.getGateId(), metricKey);
            benchmarkService.recordMetric(ingressMode, metricKey, tIngressNano, System.nanoTime(), System.nanoTime(), hardwareMs);
            return;
        }

        final String resolvedAnimalId = animalId;
        PresenceState state = book.presenceOf(resolvedAnimalId).orElseGet(() -> {
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

        // The gate's direction mode decides how the direction is found:
        //   IN_ONLY / OUT_ONLY  fixed by the gate, whatever reader saw the animal       (ONE_WAY_GATE)
        //   BIDIRECTIONAL       two readers: order of the inner and outer read           (PAIRED_READERS)
        //                       one reader: the opposite of the animal's last presence   (GATE_MODE)
        GateDirectionMode mode = GateDirectionMode.parseOrDefault(gate.getDirectionMode());
        boolean readerHasSide = binding.isPresent() && binding.get().side() != null;
        method = GateDirectionPolicy.methodFor(mode, readerHasSide);

        if (GateDirectionPolicy.PAIRED_READERS.equals(method)) {
            // 5a. Paired readers: direction is the ORDER in which the two readers saw the tag (event time).
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
            // 5b. One-way gate, or a single reader: one read is one crossing. An animal that stays in front of the
            //     reader reports several times, so a read within the debounce time (event time) of this animal's
            //     last movement at the gate counts once.
            crossingStore.lockCrossing(card, gate.getGateId());
            if (crossingStore.movementNear(resolvedAnimalId, gate.getGateId(), hardwareMs, gate.getRepeatDebounceMs())) {
                log.info("[{}] Repeat read of tag '{}' at gate '{}' ({}) ignored.", ingressMode, card, gate.getGateId(), readerName);
                benchmarkService.recordMetric(ingressMode, metricKey, tIngressNano, System.nanoTime(), System.nanoTime(), hardwareMs);
                return;
            }
            direction = GateDirectionPolicy.singleReadDirection(mode, previousStatus);
        }
        long tDedupDoneNano = System.nanoTime();
        String nextPresence = GateDirectionPolicy.presenceAfter(direction);

        // 6. WRONG_BARN: the animal is assigned to another barn
        String assignedBarnId = animals.assignedBarn(resolvedAnimalId).orElse(null);
        if (assignedBarnId != null && !assignedBarnId.equals(gate.getBarnId())) {
            log.warn("[{}] ANOMALY [WRONG_BARN]: Animal '{}' (assigned: {}) scanned at Gate '{}' (barn: {})",
                    ingressMode, resolvedAnimalId, assignedBarnId, gate.getGateId(), gate.getBarnId());
            alerts.raiseWrongBarn(resolvedAnimalId, assignedBarnId, gate.getBarnId(), readerName, direction);
        }

        // 7. An animal that comes back IN resolves its open MISSING_CATTLE alert
        if ("IN".equals(direction)) {
            alerts.resolveMissingCattle(resolvedAnimalId, readerName);
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
        book.saveMovement(movement);

        // 9. Presence and barn counters
        state.setPresenceStatus(nextPresence);
        state.setCurrentBarnId(gate.getBarnId());
        state.setLastMovementId(movementId);
        state.setUpdatedAt(Instant.now());
        book.savePresence(state);

        // Headcount: only a real change between "in a barn" and "not in a barn" moves it, whatever the method.
        List<GateDirectionPolicy.HeadcountMove> moves =
                GateDirectionPolicy.headcountMoves(direction, previousStatus, previousBarnId, gate.getBarnId());
        if (moves.isEmpty()) {
            log.warn("[{}] Animal '{}' crossed {} but was already {} (barn {}). Movement recorded, headcount unchanged.",
                    ingressMode, resolvedAnimalId, direction, previousStatus, previousBarnId);
        }
        for (GateDirectionPolicy.HeadcountMove move : moves) {
            barns.adjustHeadcount(move.barnId(), move.delta());
        }
        if ("IN".equals(direction)) {
            barns.countIn(gate.getBarnId());
        } else {
            barns.countOut(gate.getBarnId());
        }

        long tDbDoneNano = System.nanoTime();
        benchmarkService.recordMetric(ingressMode, metricKey, tIngressNano, tDedupDoneNano, tDbDoneNano, hardwareMs);
        log.info("[{}] Movement recorded: Animal '{}' -> {} at Gate '{}' via {} [Barn: {}] in {} ms",
                ingressMode, resolvedAnimalId, direction, gate.getGateId(), method, gate.getBarnId(),
                Math.round((tDbDoneNano - tIngressNano) / 10000.0) / 100.0);
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
}
