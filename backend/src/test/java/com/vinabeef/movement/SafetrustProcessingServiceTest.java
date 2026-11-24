package com.vinabeef.movement;

import com.vinabeef.facilities.Gate;
import com.vinabeef.ingestion.IngestionBenchmarkService;
import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.movement.InMemoryMovementWorld.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The movement use case, run against a farm held in memory: which reads make a movement, which direction it has, what
 * is ignored, and which alert is raised. No SQL, no Spring. (The SQL behind each port is tested in the adapter tests.)
 *
 * <p>The farm: BARN-1 has gate G-PAIR (inner reader IN-R, outer reader OUT-R), gate G-ONE (one reader, ONE-R, bidirectional)
 * and gate G-RET (one reader, RET-R, return gate). Animal A-1 wears tag TAG-1 and is assigned to BARN-1.
 */
class SafetrustProcessingServiceTest {

    private static final long T = 1_795_164_000_000L;

    private InMemoryMovementWorld world;

    @BeforeEach
    void farm() {
        world = new InMemoryMovementWorld();
        world.pairedGate("G-PAIR", "BARN-1", "IN-R", "OUT-R");
        world.singleReaderGate("G-ONE", "BARN-1", "ONE-R", "BIDIRECTIONAL");
        world.singleReaderGate("G-RET", "BARN-1", "RET-R", "IN_ONLY");
        world.animal("A-1", "TAG-1", "BARN-1");
        world.presenceOf("A-1", "OUT_PASTURE", "BARN-1");
    }

    private SafetrustProcessingService service() {
        return service(true);
    }

    private SafetrustProcessingService service(boolean matching) {
        return new SafetrustProcessingService(world, world, world, world, world, world, world,
                new IngestionBenchmarkService(), new CredentialCardResolver(world), new CrossSourceMatcher(world, matching, 10_000));
    }

    private static SafetrustEventDto read(long key, String reader, String card, long ms) {
        SafetrustEventDto d = new SafetrustEventDto();
        d.setEventKey(key);
        d.setTimestamp(ms);
        d.setReaderName(reader);
        d.setCardUuid(card);
        d.setRawPayload("{}");
        return d;
    }

    private static SafetrustEventDto usbRead(long key, String reader, String credential, long ms) {
        SafetrustEventDto d = new SafetrustEventDto();
        d.setEventKey(key);
        d.setTimestamp(ms);
        d.setReaderName(reader);
        d.setCredentialIdentifier(credential);
        d.setEdgeSource("LOCAL_USB");
        d.setEdgeDevice("USB-C0F8");
        d.setEdgeTimeSource("GATEWAY_CLOCK");
        d.setRawPayload("{}");
        return d;
    }

    private void process(SafetrustEventDto dto) {
        service().processSafetrustRead(dto, "DIRECT_MQTT", System.nanoTime());
    }

    // --- two readers: the order of the reads is the direction ------------------------------------------------------

    @Test
    void outerThenInnerWithinTheWindowIsOneMovementIn() {
        process(read(1, "OUT-R", "TAG-1", T));
        assertTrue(world.movements.isEmpty(), "one read is half a crossing");
        assertEquals(1, world.unpairedCount());

        process(read(2, "IN-R", "TAG-1", T + 2_000));

        assertEquals(1, world.movements.size());
        Movement m = world.movements.get(0);
        assertEquals("IN", m.getDirection());
        assertEquals("PAIRED_READERS", m.getDetectionMethod());
        assertEquals(Instant.ofEpochMilli(T + 2_000), m.getOccurredAt());
        assertEquals(Instant.ofEpochMilli(T), m.getCrossingStartedAt());
        assertEquals(world.row(2).eventId, m.getSourceEventId());
        assertEquals(world.row(1).eventId, m.getFirstEventId());
        assertEquals(0, world.unpairedCount(), "both reads are used up by the crossing");
        assertEquals("IN_BARN", world.presence.get("A-1").getPresenceStatus());
        assertEquals(m.getMovementId(), world.presence.get("A-1").getLastMovementId());
        assertEquals(1, world.headcount.get("BARN-1"));
        assertEquals(1, world.dailyIn.get("BARN-1"));
    }

    @Test
    void innerThenOuterIsOut() {
        world.presenceOf("A-1", "IN_BARN", "BARN-1");
        process(read(1, "IN-R", "TAG-1", T));
        process(read(2, "OUT-R", "TAG-1", T + 1_000));

        assertEquals("OUT", world.movements.get(0).getDirection());
        assertEquals("OUT_PASTURE", world.presence.get("A-1").getPresenceStatus());
        assertEquals(-1, world.headcount.get("BARN-1"));
        assertEquals(1, world.dailyOut.get("BARN-1"));
    }

    @Test
    void theDirectionComesFromEventTimeNotFromArrivalOrder() {
        process(read(1, "IN-R", "TAG-1", T + 2_000));       // arrives first, but was read second
        process(read(2, "OUT-R", "TAG-1", T));
        assertEquals("IN", world.movements.get(0).getDirection());
    }

    @Test
    void twoReadsFurtherApartThanThePairWindowAreNotOneCrossing() {
        process(read(1, "OUT-R", "TAG-1", T));
        process(read(2, "IN-R", "TAG-1", T + 20_000));      // the window is 15 s
        assertTrue(world.movements.isEmpty());
        assertEquals(2, world.unpairedCount());
    }

    @Test
    void aRepeatReadAtTheSameReaderInsideTheDebounceIsNotRemembered() {
        process(read(1, "OUT-R", "TAG-1", T));
        process(read(2, "OUT-R", "TAG-1", T + 1_000));      // the debounce is 3 s
        assertEquals(1, world.unpairedCount());
        assertTrue(world.movements.isEmpty());
    }

    @Test
    void anAnimalStillBeingReadAfterItsCrossingDoesNotStartAnother() {
        process(read(1, "OUT-R", "TAG-1", T));
        process(read(2, "IN-R", "TAG-1", T + 2_000));
        process(read(3, "OUT-R", "TAG-1", T + 3_000));      // still walking past the readers
        assertEquals(1, world.movements.size());
        assertEquals(0, world.unpairedCount());
    }

    // --- one reader, or a one-way gate: one read is one crossing ---------------------------------------------------

    @Test
    void aSingleReaderGateTurnsTheAnimalsLastPresenceAround() {
        process(read(1, "ONE-R", "TAG-1", T));                       // was out: comes in
        process(read(2, "ONE-R", "TAG-1", T + 10_000));              // was in: goes out
        assertEquals(List.of("IN", "OUT"), world.movements.stream().map(Movement::getDirection).toList());
        assertEquals("GATE_MODE", world.movements.get(0).getDetectionMethod());
        assertEquals(0, world.headcount.get("BARN-1"));
    }

    @Test
    void aRepeatReadAtASingleReaderGateCountsOnce() {
        process(read(1, "ONE-R", "TAG-1", T));
        process(read(2, "ONE-R", "TAG-1", T + 1_000));
        assertEquals(1, world.movements.size());
        assertEquals(1, world.headcount.get("BARN-1"));
    }

    @Test
    void aReturnGateIsAlwaysInAndAnAnimalAlreadyInDoesNotMoveTheHeadcount() {
        world.presenceOf("A-1", "IN_BARN", "BARN-1");
        process(read(1, "RET-R", "TAG-1", T));
        assertEquals("IN", world.movements.get(0).getDirection());
        assertEquals("ONE_WAY_GATE", world.movements.get(0).getDetectionMethod());
        assertNull(world.headcount.get("BARN-1"), "it was already counted");
        assertEquals(1, world.dailyIn.get("BARN-1"), "but the crossing is still counted for the day");
    }

    @Test
    void anAnimalWithNoPresenceYetStartsOutInThePasture() {
        world.presence.clear();
        process(read(1, "ONE-R", "TAG-1", T));
        assertEquals("IN", world.movements.get(0).getDirection());
        assertEquals("IN_BARN", world.presence.get("A-1").getPresenceStatus());
        assertEquals("BARN-1", world.presence.get("A-1").getCurrentBarnId());
    }

    // --- who the animal is, and what is raised when something is wrong ---------------------------------------------

    @Test
    void aTagNobodyWearsRaisesOneAlertAndMakesNoMovement() {
        process(read(1, "ONE-R", "TAG-X", T));
        assertEquals(List.of("UNEXPECTED_TAG"), world.alertKinds());
        assertEquals(List.<Object>of("BARN-1", "TAG-X", "UNKNOWN", "ONE-R", "G-ONE", 1L), world.alerts.get(0).args());
        assertTrue(world.movements.isEmpty());
        assertEquals(1, world.log.size(), "the read itself is still kept");
    }

    @Test
    void theAnimalNamedInTheEventIsUsedWhenTheTagIsNotAssigned() {
        world.animalBarn.put("A-9", "BARN-1");
        SafetrustEventDto d = read(1, "ONE-R", "TAG-NEW", T);
        d.setNotes(new HashMap<>(Map.of("USER_NAME", "A-9")));
        process(d);
        assertEquals("A-9", world.movements.get(0).getAnimalId());
        assertTrue(world.alerts.isEmpty());
    }

    @Test
    void anArchivedAnimalNamedInTheEventIsNotUsed() {
        world.animalBarn.put("A-9", "BARN-1");
        world.archivedAnimals.add("A-9");
        SafetrustEventDto d = read(1, "ONE-R", "TAG-NEW", T);
        d.setNotes(new HashMap<>(Map.of("USER_NAME", "A-9")));
        process(d);
        assertTrue(world.movements.isEmpty());
        assertEquals(List.of("UNEXPECTED_TAG"), world.alertKinds());
        assertEquals("A-9", world.alerts.get(0).args().get(2));
    }

    @Test
    void aReaderNobodyRegisteredRaisesTheReaderAlert() {
        process(read(1, "GHOST-R", "TAG-1", T));
        assertEquals(List.of("UNREGISTERED_READER"), world.alertKinds());
        assertEquals(List.<Object>of("GHOST-R", "TAG-1", 1L), world.alerts.get(0).args());
        assertTrue(world.movements.isEmpty());
    }

    @Test
    void aRetiredGateIsNoLongerAReader() {
        Gate g = world.gates.get("G-ONE");
        g.setRetiredAt(Instant.ofEpochMilli(T - 1));
        process(read(1, "ONE-R", "TAG-1", T));
        assertEquals(List.of("UNREGISTERED_READER"), world.alertKinds());
        assertTrue(world.movements.isEmpty());
    }

    @Test
    void anAnimalAssignedToAnotherBarnIsStillRecordedAndRaisesWrongBarn() {
        world.animalBarn.put("A-1", "BARN-2");
        process(read(1, "ONE-R", "TAG-1", T));
        assertEquals(1, world.movements.size());
        assertEquals(List.of("WRONG_BARN"), world.alertKinds());
        assertEquals(List.<Object>of("A-1", "BARN-2", "BARN-1", "ONE-R", "IN"), world.alerts.get(0).args());
    }

    @Test
    void anAnimalComingBackInClosesItsMissingAlertAndOneGoingOutDoesNot() {
        process(read(1, "ONE-R", "TAG-1", T));                       // in
        process(read(2, "ONE-R", "TAG-1", T + 10_000));              // out
        assertEquals(List.of("A-1@ONE-R"), world.resolvedMissing);
    }

    @Test
    void anAnimalMovingBetweenBarnsMovesTheHeadcountOfBoth() {
        world.presenceOf("A-1", "IN_BARN", "BARN-2");
        process(read(1, "RET-R", "TAG-1", T));
        assertEquals(-1, world.headcount.get("BARN-2"));
        assertEquals(1, world.headcount.get("BARN-1"));
    }

    // --- replays and the two routes ---------------------------------------------------------------------------------

    @Test
    void aReplayOfTheSameEventKeyDoesNothingMore() {
        SafetrustEventDto d = read(1, "ONE-R", "TAG-1", T);
        process(d);
        process(d);
        assertEquals(1, world.log.size());
        assertEquals(1, world.movements.size());
        assertEquals(1, world.headcount.get("BARN-1"));
    }

    @Test
    void theRawReadIsStoredBeforeAnythingElseAndWithItsRoute() {
        service().processSafetrustRead(read(1, "GHOST-R", "TAG-1", T), "EDGE_BATCH", 0L);
        Row r = world.row(1);
        assertEquals("EDGE_BATCH", r.ingressMode);
        assertEquals("CARD_READ", r.eventClass);
        assertEquals("SAFETRUST", r.readSource);
        assertEquals(T, r.hardwareMs);
    }

    @Test
    void aUsbReadWhoseNumberIsKnownIsStoredWithItsCardAndHowItWasFound() {
        world.historyCards.put("38", List.of("TAG-1"));
        service().processSafetrustRead(usbRead(-1_000_000_007L, "ONE-R", "38", T), "EDGE_BATCH", 0L);

        Row r = world.row(-1_000_000_007L);
        assertEquals("TAG-1", r.card);                         // resolved before the row was written
        assertEquals("38", r.credential);
        assertEquals("CARD_READ", r.eventClass);
        assertEquals("LOCAL_USB", r.readSource);
        assertEquals("USB-C0F8", r.sourceDevice);
        assertEquals("SAFETRUST_HISTORY", r.resolvedBy);
        assertEquals(1, world.movements.size(), "a resolved USB read is an ordinary read");
    }

    @Test
    void aUsbReadIsNeverTreatedAsThePairedReaderBeingAlive() {
        world.historyCards.put("38", List.of("TAG-1"));
        service().processSafetrustRead(usbRead(-1, "IN-R", "38", T), "EDGE_BATCH", 0L);
        assertTrue(world.lastSeen.isEmpty(), "last-seen of the wall reader must not move because of a USB read");

        process(read(2, "IN-R", "TAG-1", T + 60_000));
        assertEquals(T + 60_000, world.lastSeen.get("IN-R"));
    }

    @Test
    void theSecondCopyOfATapIsKeptAsEvidenceAndMakesNoMovement() {
        world.historyCards.put("38", List.of("TAG-1"));
        process(read(1, "ONE-R", "TAG-1", T));
        service().processSafetrustRead(usbRead(-2, "ONE-R", "38", T + 400), "EDGE_BATCH", 0L);

        assertEquals(1, world.movements.size());
        assertEquals(world.row(1).eventId, world.row(-2).duplicateOf);
        assertEquals(world.row(-2).eventId, world.row(1).twin);
        assertTrue(world.alerts.isEmpty());
        assertEquals(1, world.headcount.get("BARN-1"));
    }

    @Test
    void theFirstCopyCountsWhicheverRouteDeliversItFirst() {
        world.historyCards.put("38", List.of("TAG-1"));
        service().processSafetrustRead(usbRead(-2, "ONE-R", "38", T), "EDGE_BATCH", 0L);
        process(read(1, "ONE-R", "TAG-1", T + 400));

        assertEquals(1, world.movements.size());
        assertEquals(world.row(-2).eventId, world.row(1).duplicateOf);
    }

    @Test
    void twoRealTapsOnTheSameRouteStayTwoTaps() {
        process(read(1, "ONE-R", "TAG-1", T));
        process(read(2, "ONE-R", "TAG-1", T + 20_000));
        assertEquals(2, world.movements.size());
        assertNull(world.row(2).duplicateOf);
    }

    @Test
    void withMatchingSwitchedOffBothCopiesAreKeptAndNothingIsMarked() {
        world.historyCards.put("38", List.of("TAG-1"));
        service(false).processSafetrustRead(read(1, "ONE-R", "TAG-1", T), "DIRECT_MQTT", 0L);
        service(false).processSafetrustRead(usbRead(-2, "ONE-R", "38", T + 400), "EDGE_BATCH", 0L);
        assertNull(world.row(-2).duplicateOf);
        assertNull(world.row(1).twin);
    }

    // --- a USB read whose number maps to no card -----------------------------------------------------------------------

    @Test
    void aUsbReadWhoseNumberMatchesNoCardIsKeptAsUnmappedAndOnlyNoticed() {
        service().processSafetrustRead(usbRead(-3, "ONE-R", "999", T), "EDGE_BATCH", 0L);

        Row r = world.row(-3);
        assertNull(r.card, "no card is invented");
        assertEquals("999", r.credential);
        assertEquals("LOCAL_UNMAPPED", r.eventClass);
        assertNull(r.resolvedBy);
        assertEquals(List.of("UNMAPPED_CREDENTIAL"), world.alertKinds());
        assertEquals(List.<Object>of("BARN-1", "999", "ONE-R", "G-ONE", -3L), world.alerts.get(0).args());
        assertTrue(world.movements.isEmpty());
        assertTrue(world.lastSeen.isEmpty());
    }

    @Test
    void anUnmappedUsbReadFromAnUnknownReaderRaisesNoAlert() {
        service().processSafetrustRead(usbRead(-3, "GHOST-R", "999", T), "EDGE_BATCH", 0L);
        assertEquals("LOCAL_UNMAPPED", world.row(-3).eventClass);
        assertTrue(world.alerts.isEmpty());
    }

    @Test
    void anUnmappedReadIsRetriedAndBecomesAnOrdinaryReadOnceItsNumberIsKnown() {
        SafetrustProcessingService service = service();
        service.processSafetrustRead(usbRead(-3, "ONE-R", "38", T), "EDGE_BATCH", 0L);
        String id = world.row(-3).eventId;
        assertEquals(1, service.countUnmappedLocalReads());
        assertEquals(List.of(id), service.unmappedLocalReadIds(10));
        assertFalse(service.reprocessUnmappedLocalRead(id), "nothing is known about 38 yet");
        assertTrue(world.movements.isEmpty());

        world.registerCards.put("38", List.of("TAG-1"));         // somebody enters the number in the tag register
        assertTrue(service.reprocessUnmappedLocalRead(id));

        Row r = world.row(-3);
        assertEquals("CARD_READ", r.eventClass);
        assertEquals("TAG-1", r.card);
        assertEquals("TAG_REGISTER", r.resolvedBy);
        assertEquals(0, service.countUnmappedLocalReads());
        assertEquals(1, world.movements.size());
        assertEquals(Instant.ofEpochMilli(T), world.movements.get(0).getOccurredAt(), "from the time the read was taken");
        assertEquals(id, world.movements.get(0).getSourceEventId());
        assertFalse(service.reprocessUnmappedLocalRead(id), "a resolved read is not processed twice");
    }

    @Test
    void retryingAReadThatDoesNotExistIsFalse() {
        assertFalse(service().reprocessUnmappedLocalRead("no-such-event"));
    }

    // --- reader status events: kept, no movement ---------------------------------------------------------------------

    private static SafetrustEventDto readerStatus(long key, String reader, String action, long ms) {
        SafetrustEventDto d = new SafetrustEventDto();
        d.setEventKey(key);
        d.setTimestamp(ms);
        d.setReaderName(reader);
        d.setIpAddress("10.0.0.5");
        d.setNotes(new HashMap<>(Map.of("ACTION", action, "READER_NAME", reader)));
        d.setRawPayload("{}");
        return d;
    }

    @Test
    void aReaderConnectingAndDisconnectingIsRecordedAndMakesNoMovement() {
        process(readerStatus(1, "IN-R", "READER_CONNECT_MQTT", T));
        assertEquals("ONLINE", world.connection.get("IN-R"));
        assertEquals(T, world.lastSeen.get("IN-R"));
        process(readerStatus(2, "IN-R", "READER_DISCONNECT_MQTT", T + 1_000));
        assertEquals("OFFLINE", world.connection.get("IN-R"));
        assertTrue(world.movements.isEmpty());
        assertEquals("READER_STATUS", world.row(1).eventClass);
    }
}
