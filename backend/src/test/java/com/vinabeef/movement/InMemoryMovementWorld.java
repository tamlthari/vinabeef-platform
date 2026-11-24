package com.vinabeef.movement;

import com.vinabeef.facilities.Gate;
import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.movement.GateCrossingResolver.Known;
import com.vinabeef.movement.GateCrossingResolver.Side;
import com.vinabeef.movement.port.AlertSink;
import com.vinabeef.movement.port.AnimalRegistry;
import com.vinabeef.movement.port.BarnCounters;
import com.vinabeef.movement.port.CredentialLookup;
import com.vinabeef.movement.port.GateCrossingStore;
import com.vinabeef.movement.port.GateLookup;
import com.vinabeef.movement.port.MovementBook;
import com.vinabeef.movement.port.ReadEventLog;
import com.vinabeef.movement.port.ReadTwinStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A farm held in memory, standing behind every output port of the movement use case. The use case runs against it
 * exactly as it runs against the database, so the rules (pairing, direction, repeats, alerts, counters, replays)
 * are tested without SQL, Spring or a connection. The SQL behind each port is tested on its own, in the adapter tests.
 */
final class InMemoryMovementWorld implements AnimalRegistry, GateLookup, MovementBook, BarnCounters, AlertSink,
        ReadEventLog, GateCrossingStore, CredentialLookup, ReadTwinStore {

    /** One stored raw read. */
    static final class Row {
        String eventId;
        Long eventKey;
        String readerName;
        String credential;
        String card;
        String eventClass;
        long hardwareMs;
        String ingressMode;
        String readSource;
        String sourceDevice;
        String resolvedBy;
        String duplicateOf;
        String twin;
    }

    /** One alert, by kind (UNEXPECTED_TAG, UNMAPPED_CREDENTIAL, WRONG_BARN, UNREGISTERED_READER). */
    record Alert(String kind, List<Object> args) {
    }

    // --- the farm ---------------------------------------------------------------------------------------------
    final Map<String, Gate> gates = new LinkedHashMap<>();
    final Map<String, ReaderBinding> readers = new HashMap<>();
    final Map<String, String> tagToAnimal = new HashMap<>();
    final Map<String, String> animalBarn = new HashMap<>();
    final Set<String> archivedAnimals = new HashSet<>();
    final Map<String, List<String>> historyCards = new HashMap<>();
    final Map<String, List<String>> registerCards = new HashMap<>();

    // --- what the use case writes -----------------------------------------------------------------------------
    final List<Row> log = new ArrayList<>();
    final Map<String, PresenceState> presence = new HashMap<>();
    final List<Movement> movements = new ArrayList<>();
    final Map<String, Integer> headcount = new HashMap<>();
    final Map<String, Integer> dailyIn = new HashMap<>();
    final Map<String, Integer> dailyOut = new HashMap<>();
    final List<Alert> alerts = new ArrayList<>();
    final List<String> resolvedMissing = new ArrayList<>();
    final Map<String, Long> lastSeen = new HashMap<>();
    final Map<String, String> connection = new HashMap<>();

    // --- set-up helpers ---------------------------------------------------------------------------------------

    /** A gate with one reader that is not part of a pair. */
    Gate singleReaderGate(String gateId, String barnId, String readerName, String directionMode) {
        Gate g = gate(gateId, barnId, directionMode);
        g.setReaderName(readerName);
        return g;
    }

    /** A bidirectional gate with an inner and an outer reader. */
    Gate pairedGate(String gateId, String barnId, String innerReader, String outerReader) {
        return pairedGate(gateId, barnId, innerReader, outerReader, "BIDIRECTIONAL");
    }

    /** A gate with an inner and an outer reader and the given direction mode (a one-way mode here is a leftover). */
    Gate pairedGate(String gateId, String barnId, String innerReader, String outerReader, String directionMode) {
        Gate g = gate(gateId, barnId, directionMode);
        readers.put(innerReader, new ReaderBinding(innerReader, gateId, Side.INNER));
        readers.put(outerReader, new ReaderBinding(outerReader, gateId, Side.OUTER));
        return g;
    }

    private Gate gate(String gateId, String barnId, String directionMode) {
        Gate g = new Gate();
        g.setGateId(gateId);
        g.setBarnId(barnId);
        g.setLabel(gateId);
        g.setDirectionMode(directionMode);
        gates.put(gateId, g);
        return g;
    }

    void animal(String animalId, String tagEpc, String assignedBarn) {
        tagToAnimal.put(tagEpc, animalId);
        animalBarn.put(animalId, assignedBarn);
    }

    void presenceOf(String animalId, String status, String barnId) {
        PresenceState s = new PresenceState();
        s.setAnimalId(animalId);
        s.setPresenceStatus(status);
        s.setCurrentBarnId(barnId);
        presence.put(animalId, s);
    }

    Row row(long eventKey) {
        return log.stream().filter(r -> r.eventKey != null && r.eventKey == eventKey).findFirst().orElseThrow();
    }

    // --- AnimalRegistry ---------------------------------------------------------------------------------------
    @Override
    public Optional<String> activeAnimalForTag(String tagEpc) {
        return Optional.ofNullable(tagToAnimal.get(tagEpc));
    }

    @Override
    public boolean isLiveAnimal(String animalId) {
        return animalBarn.containsKey(animalId) && !archivedAnimals.contains(animalId);
    }

    @Override
    public Optional<String> assignedBarn(String animalId) {
        return Optional.ofNullable(animalBarn.get(animalId));
    }

    // --- GateLookup -------------------------------------------------------------------------------------------
    @Override
    public Optional<Gate> byId(String gateId) {
        return Optional.ofNullable(gates.get(gateId));
    }

    @Override
    public Optional<Gate> activeByReaderName(String readerName) {
        return gates.values().stream()
                .filter(g -> readerName.equals(g.getReaderName()) && g.getRetiredAt() == null)
                .findFirst();
    }

    // --- MovementBook -----------------------------------------------------------------------------------------
    @Override
    public Optional<PresenceState> presenceOf(String animalId) {
        return Optional.ofNullable(presence.get(animalId));
    }

    @Override
    public void saveMovement(Movement movement) {
        // like the repository: a movement that is already stored is updated, not stored twice
        if (movements.stream().noneMatch(m -> m.getMovementId().equals(movement.getMovementId()))) {
            movements.add(movement);
        }
    }

    @Override
    public Optional<Movement> findMovement(String movementId) {
        return movements.stream().filter(m -> m.getMovementId().equals(movementId)).findFirst();
    }

    @Override
    public void savePresence(PresenceState state) {
        presence.put(state.getAnimalId(), state);
    }

    // --- BarnCounters -----------------------------------------------------------------------------------------
    @Override
    public void adjustHeadcount(String barnId, int delta) {
        headcount.merge(barnId, delta, Integer::sum);
    }

    @Override
    public void countIn(String barnId) {
        dailyIn.merge(barnId, 1, Integer::sum);
    }

    @Override
    public void countOut(String barnId) {
        dailyOut.merge(barnId, 1, Integer::sum);
    }

    @Override
    public void uncountIn(String barnId) {
        dailyIn.merge(barnId, -1, (a, b) -> Math.max(a + b, 0));
    }

    @Override
    public void uncountOut(String barnId) {
        dailyOut.merge(barnId, -1, (a, b) -> Math.max(a + b, 0));
    }

    // --- AlertSink --------------------------------------------------------------------------------------------
    @Override
    public void raiseUnexpectedTag(String barnId, String tagEpc, String userName, String readerName, String gateId, long eventKey) {
        alerts.add(new Alert("UNEXPECTED_TAG", List.of(barnId, tagEpc, userName, readerName, gateId, eventKey)));
    }

    @Override
    public void raiseUnmappedCredential(String barnId, String credential, String readerName, String gateId, long eventKey) {
        alerts.add(new Alert("UNMAPPED_CREDENTIAL", List.of(barnId, credential, readerName, gateId, eventKey)));
    }

    @Override
    public void raiseWrongBarn(String animalId, String assignedBarn, String detectedBarn, String readerName, String direction) {
        alerts.add(new Alert("WRONG_BARN", List.of(animalId, assignedBarn, detectedBarn, readerName, direction)));
    }

    @Override
    public void raiseUnregisteredReader(String readerName, String cardUuid, long eventKey) {
        alerts.add(new Alert("UNREGISTERED_READER", List.of(readerName, cardUuid, eventKey)));
    }

    @Override
    public void resolveMissingCattle(String animalId, String readerName) {
        resolvedMissing.add(animalId + "@" + readerName);
    }

    List<String> alertKinds() {
        return alerts.stream().map(Alert::kind).toList();
    }

    // --- ReadEventLog -----------------------------------------------------------------------------------------
    @Override
    public boolean storeIfNew(String eventId, SafetrustEventDto dto, String eventClass,
                              long hardwareMs, String ingressMode, String cardResolvedBy) {
        if (dto.getEventKey() != null && log.stream().anyMatch(r -> dto.getEventKey().equals(r.eventKey))) {
            return false;
        }
        Row r = new Row();
        r.eventId = eventId;
        r.eventKey = dto.getEventKey();
        r.readerName = dto.getReaderName() != null ? dto.getReaderName() : "UNKNOWN";
        r.credential = dto.getCredentialIdentifier();
        r.card = dto.getCardUuid();
        r.eventClass = eventClass;
        r.hardwareMs = hardwareMs;
        r.ingressMode = ingressMode;
        r.readSource = dto.getReadSource();
        r.sourceDevice = dto.getEdgeDevice();
        r.resolvedBy = cardResolvedBy;
        log.add(r);
        return true;
    }

    @Override
    public Optional<UnmappedRead> findUnmapped(String eventId) {
        return log.stream()
                .filter(r -> r.eventId.equals(eventId) && "LOCAL_UNMAPPED".equals(r.eventClass))
                .findFirst()
                .map(r -> new UnmappedRead(r.eventKey, r.readerName, r.credential, r.hardwareMs, r.sourceDevice));
    }

    @Override
    public void markResolved(String eventId, String cardUuid, String resolvedBy) {
        log.stream().filter(r -> r.eventId.equals(eventId) && "LOCAL_UNMAPPED".equals(r.eventClass)).forEach(r -> {
            r.card = cardUuid;
            r.resolvedBy = resolvedBy;
            r.eventClass = "CARD_READ";
        });
    }

    @Override
    public List<String> unmappedIds(int limit) {
        return log.stream().filter(r -> "LOCAL_UNMAPPED".equals(r.eventClass))
                .sorted(Comparator.comparingLong((Row r) -> r.hardwareMs).thenComparing(r -> r.eventId))
                .limit(limit).map(r -> r.eventId).toList();
    }

    @Override
    public int countUnmapped() {
        return (int) log.stream().filter(r -> "LOCAL_UNMAPPED".equals(r.eventClass)).count();
    }

    // --- CredentialLookup -------------------------------------------------------------------------------------
    @Override
    public List<String> cardsByHistory(String credential) {
        return historyCards.getOrDefault(credential, List.of());
    }

    @Override
    public List<String> cardsByRegister(String credential) {
        return registerCards.getOrDefault(credential, List.of());
    }

    // --- ReadTwinStore ----------------------------------------------------------------------------------------
    @Override
    public void lock(String card, String readerName) {
        // one thread, nothing to serialise
    }

    @Override
    public List<String> findTwin(String eventId, String readerName, String card, String readSource, long hardwareMs, long windowMs) {
        return log.stream()
                .filter(r -> r.readerName.equals(readerName) && card.equals(r.card) && "CARD_READ".equals(r.eventClass)
                        && r.duplicateOf == null && r.twin == null
                        && !r.readSource.equals(readSource) && !r.eventId.equals(eventId)
                        && r.hardwareMs >= hardwareMs - windowMs && r.hardwareMs <= hardwareMs + windowMs)
                .sorted(Comparator.comparingLong((Row r) -> Math.abs(r.hardwareMs - hardwareMs))
                        .thenComparingLong(r -> r.hardwareMs).thenComparing(r -> r.eventId))
                .limit(1).map(r -> r.eventId).toList();
    }

    @Override
    public boolean markPrimary(String first, String eventId) {
        for (Row r : log) {
            if (r.eventId.equals(first) && r.twin == null && r.duplicateOf == null) {
                r.twin = eventId;
                return true;
            }
        }
        return false;
    }

    @Override
    public void markDuplicate(String eventId, String first) {
        log.stream().filter(r -> r.eventId.equals(eventId)).forEach(r -> r.duplicateOf = first);
    }

    // --- GateCrossingStore ------------------------------------------------------------------------------------
    @Override
    public Optional<ReaderBinding> findReader(String readerName) {
        return Optional.ofNullable(readerName == null ? null : readers.get(readerName));
    }

    @Override
    public void lockCrossing(String tagEpc, String gateId) {
        // one thread, nothing to serialise
    }

    @Override
    public Neighbours neighbours(String animalId, String gateId, long eventMs) {
        List<Movement> here = movements.stream()
                .filter(m -> m.getAnimalId().equals(animalId) && m.getGateId().equals(gateId)).toList();
        Comparator<Movement> byTime = Comparator.comparing(Movement::getOccurredAt).thenComparing(Movement::getMovementId);
        Movement previous = here.stream().filter(m -> m.getOccurredAt().toEpochMilli() <= eventMs).max(byTime).orElse(null);
        Movement next = here.stream().filter(m -> m.getOccurredAt().toEpochMilli() > eventMs).min(byTime).orElse(null);
        return new Neighbours(known(previous), known(next));
    }

    private static Known known(Movement m) {
        if (m == null) {
            return null;
        }
        long at = m.getOccurredAt().toEpochMilli();
        return new Known(m.getMovementId(), Movement.HALF_CROSSING.equals(m.getCrossingStatus()),
                Side.parse(m.getReaderSide()), m.getCrossingStartedAt() != null ? m.getCrossingStartedAt().toEpochMilli() : at, at);
    }

    /** The animal's movements at a gate, oldest first. */
    List<Movement> movementsOf(String animalId, String gateId) {
        return movements.stream().filter(m -> m.getAnimalId().equals(animalId) && m.getGateId().equals(gateId))
                .sorted(Comparator.comparing(Movement::getOccurredAt).thenComparing(Movement::getMovementId)).toList();
    }

    @Override
    public boolean movementNear(String animalId, String gateId, long eventMs, long debounceMs) {
        if (debounceMs <= 0) {
            return false;
        }
        return movements.stream().anyMatch(m -> m.getAnimalId().equals(animalId) && m.getGateId().equals(gateId)
                && within(m, eventMs, debounceMs));
    }

    private static boolean within(Movement m, long eventMs, long debounceMs) {
        Instant at = m.getOccurredAt();
        return at.toEpochMilli() > eventMs - debounceMs && at.toEpochMilli() < eventMs + debounceMs;
    }

    @Override
    public void touchReader(String readerName, long eventMs) {
        // like the SQL, only a reader listed in gate_readers (one side of a pair) has a last-seen time
        if (readerName != null && readers.containsKey(readerName)) {
            lastSeen.merge(readerName, eventMs, Math::max);
        }
    }

    @Override
    public void setConnection(String readerName, String status, String ipAddress, long eventMs) {
        if (readerName != null) {
            connection.put(readerName, status);
        }
    }
}
