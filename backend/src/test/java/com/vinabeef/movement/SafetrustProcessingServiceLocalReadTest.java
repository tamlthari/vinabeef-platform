package com.vinabeef.movement;

import com.vinabeef.cattle.TagAssignmentRepository;
import com.vinabeef.facilities.BarnRepository;
import com.vinabeef.facilities.GateRepository;
import com.vinabeef.ingestion.IngestionBenchmarkService;
import com.vinabeef.ingestion.SafetrustEventDto;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real service, with fakes for the database: what happens to a read from the gateway's USB reader, in which order, and with
 * which arguments. The raw-event INSERT has 25 positional arguments; the three new columns and the card are pinned here.
 */
class SafetrustProcessingServiceLocalReadTest {

    private static final long T = 1_795_164_000_000L;

    /** A repository that knows nothing: Optional results are empty, everything else is null/0/false. */
    @SuppressWarnings("unchecked")
    private static <R> R emptyRepository(Class<R> type) {
        return (R) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Class<?> rt = method.getReturnType();
            if (rt == Optional.class) return Optional.empty();
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            return null;
        });
    }

    private static SafetrustProcessingService service(ScriptedJdbc jdbc, boolean matching) {
        return new SafetrustProcessingService(
                emptyRepository(TagAssignmentRepository.class), emptyRepository(GateRepository.class),
                null, null, emptyRepository(BarnRepository.class), new IngestionBenchmarkService(), jdbc,
                new GateCrossingStore(jdbc), new CredentialCardResolver(jdbc), new CrossSourceMatcher(jdbc, matching, 10_000));
    }

    private static SafetrustEventDto usbRead(String credential) {
        SafetrustEventDto d = new SafetrustEventDto();
        d.setEventKey(-1_000_000_007L);
        d.setTimestamp(T);
        d.setReaderName("DrC0F8");
        d.setCredentialIdentifier(credential);
        d.setEdgeSource("LOCAL_USB");
        d.setEdgeDevice("USB-C0F8");
        d.setEdgeTimeSource("GATEWAY_CLOCK");
        d.setRawPayload("{\"EVENT_KEY\":-1000000007}");
        return d;
    }

    private static SafetrustEventDto safetrustRead() {
        SafetrustEventDto d = new SafetrustEventDto();
        d.setEventKey(580_307_087L);
        d.setTimestamp(T);
        d.setReaderName("DrC0F8");
        d.setCardUuid("044E6602AD1490");
        d.setCredentialIdentifier("38");
        d.setRawPayload("{}");
        return d;
    }

    @Test
    void aUsbReadWhoseNumberIsKnownIsStoredWithItsCardAndHowItWasFound() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("044E6602AD1490");           // the history knows 38
        service(jdbc, true).processSafetrustRead(usbRead("38"), "EDGE_BATCH", 0L);

        Object[] raw = jdbc.lastArgs.get("RAW_INSERT");
        assertEquals(25, raw.length);
        assertEquals(-1_000_000_007L, raw[1]);                  // EVENT_KEY (negative: a local key)
        assertEquals("DrC0F8", raw[5]);
        assertEquals("044E6602AD1490", raw[6]);                 // CARD_UUID was resolved before the row was written
        assertEquals("38", raw[7]);                             // CREDENTIAL_IDENTIFIER
        assertEquals(T, raw[10]);
        assertEquals("EDGE_BATCH", raw[13]);
        assertEquals("CARD_READ", raw[14]);
        assertEquals("LOCAL_USB", raw[21]);                     // read_source
        assertEquals("USB-C0F8", raw[22]);                      // source_device
        assertEquals("GATEWAY_CLOCK", raw[23]);                 // time_source
        assertEquals("SAFETRUST_HISTORY", raw[24]);             // card_resolved_by
    }

    @Test
    void aUsbReadIsNeverTreatedAsTheWallReaderBeingAlive() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("044E6602AD1490");
        service(jdbc, true).processSafetrustRead(usbRead("38"), "EDGE_BATCH", 0L);
        assertFalse(jdbc.calls.contains("TOUCH"), "last-seen of the wall reader must not move because of a USB read");
        assertEquals(List.of("HISTORY", "RAW_INSERT", "FIND_READER", "LOCK", "FIND_TWIN", "ALERT_UNREGISTERED"), jdbc.calls);
    }

    @Test
    void aSafetrustReadStillMarksTheReaderAsSeen() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        service(jdbc, true).processSafetrustRead(safetrustRead(), "DIRECT_MQTT", 0L);
        assertEquals(List.of("RAW_INSERT", "FIND_READER", "TOUCH", "LOCK", "FIND_TWIN", "ALERT_UNREGISTERED"), jdbc.calls);
        Object[] raw = jdbc.lastArgs.get("RAW_INSERT");
        assertEquals("SAFETRUST", raw[21]);
        assertNull(raw[22]);
        assertNull(raw[23]);
        assertNull(raw[24]);
    }

    @Test
    void theSecondCopyOfATapStopsBeforeAnyGateWorkAndRaisesNoAlert() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("FIRST-EVENT-ID");           // the matcher finds the first copy
        service(jdbc, true).processSafetrustRead(safetrustRead(), "DIRECT_MQTT", 0L);
        assertEquals(List.of("RAW_INSERT", "FIND_READER", "TOUCH", "LOCK", "FIND_TWIN", "MARK_PRIMARY", "MARK_DUPLICATE"), jdbc.calls);
    }

    @Test
    void aUsbReadWhoseNumberMatchesNoCardIsKeptAsUnmappedAndOnlyNoticed() {
        ScriptedJdbc jdbc = new ScriptedJdbc();                                          // history and register know nothing
        service(jdbc, true).processSafetrustRead(usbRead("999"), "EDGE_BATCH", 0L);

        Object[] raw = jdbc.lastArgs.get("RAW_INSERT");
        assertNull(raw[6], "no card is invented");
        assertEquals("999", raw[7]);
        assertEquals("LOCAL_UNMAPPED", raw[14]);
        assertEquals("LOCAL_USB", raw[21]);
        assertNull(raw[24]);
        assertEquals(List.of("HISTORY", "REGISTER", "RAW_INSERT", "FIND_READER"), jdbc.calls,
                "only the reader is looked up (for the alert): no matching, no reader touch, no movement work");
    }

    @Test
    void aReplayOfTheSameEventKeyDoesNothingMore() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("044E6602AD1490").answerUpdate(0);   // the INSERT finds the key already stored
        service(jdbc, true).processSafetrustRead(usbRead("38"), "EDGE_BATCH", 0L);
        assertEquals(List.of("HISTORY", "RAW_INSERT"), jdbc.calls);
    }

    @Test
    void withMatchingSwitchedOffNoMatchingStatementRuns() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        service(jdbc, false).processSafetrustRead(safetrustRead(), "DIRECT_MQTT", 0L);
        assertTrue(jdbc.calls.stream().noneMatch(c -> c.equals("LOCK") || c.equals("FIND_TWIN")));
    }
}
