package com.vinabeef.movement.adapter;

import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.movement.port.ReadEventLog.UnmappedRead;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The raw-event INSERT has 25 positional arguments. The three columns for a USB read and the card are pinned here: a
 * swapped argument would still run and quietly store the wrong value.
 */
class JdbcReadEventLogTest {

    private static final long T = 1_795_164_000_000L;

    private static SafetrustEventDto usbRead() {
        SafetrustEventDto d = new SafetrustEventDto();
        d.setEventKey(-1_000_000_007L);
        d.setTimestamp(T);
        d.setReaderName("DrC0F8");
        d.setCredentialIdentifier("38");
        d.setCardUuid("044E6602AD1490");
        d.setEdgeSource("LOCAL_USB");
        d.setEdgeDevice("USB-C0F8");
        d.setEdgeTimeSource("GATEWAY_CLOCK");
        d.setRawPayload("{\"EVENT_KEY\":-1000000007}");
        return d;
    }

    @Test
    void aUsbReadIsStoredWithItsCardAndHowItWasFound() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        boolean stored = new JdbcReadEventLog(jdbc).storeIfNew("EV-1", usbRead(), "CARD_READ", T, "EDGE_BATCH", "SAFETRUST_HISTORY");

        assertTrue(stored);
        Object[] raw = jdbc.lastArgs.get("RAW_INSERT");
        assertEquals(25, raw.length);
        assertEquals("EV-1", raw[0]);
        assertEquals(-1_000_000_007L, raw[1]);                  // EVENT_KEY (negative: a local key)
        assertEquals("DrC0F8", raw[5]);
        assertEquals("044E6602AD1490", raw[6]);                 // CARD_UUID
        assertEquals("38", raw[7]);                             // CREDENTIAL_IDENTIFIER
        assertEquals(T, raw[10]);
        assertEquals("{\"EVENT_KEY\":-1000000007}", raw[12]);
        assertEquals("EDGE_BATCH", raw[13]);
        assertEquals("CARD_READ", raw[14]);
        assertEquals("LOCAL_USB", raw[21]);                     // read_source
        assertEquals("USB-C0F8", raw[22]);                      // source_device
        assertEquals("GATEWAY_CLOCK", raw[23]);                 // time_source
        assertEquals("SAFETRUST_HISTORY", raw[24]);             // card_resolved_by
    }

    @Test
    void aSafetrustReadLeavesTheThreeUsbColumnsAndTheResolverEmpty() {
        SafetrustEventDto d = new SafetrustEventDto();
        d.setEventKey(580_307_087L);
        d.setTimestamp(T);
        d.setReaderName("DrC0F8");
        d.setCardUuid("044E6602AD1490");
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcReadEventLog(jdbc).storeIfNew("EV-2", d, "CARD_READ", T, "DIRECT_MQTT", null);

        Object[] raw = jdbc.lastArgs.get("RAW_INSERT");
        assertEquals("SAFETRUST", raw[21]);
        assertNull(raw[22]);
        assertNull(raw[23]);
        assertNull(raw[24]);
        assertEquals("{}", raw[12], "a missing payload is stored as an empty object");
    }

    @Test
    void aMissingReaderNameIsStoredAsUnknown() {
        SafetrustEventDto d = new SafetrustEventDto();
        d.setEventKey(5L);
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcReadEventLog(jdbc).storeIfNew("EV-3", d, "OTHER", T, "DIRECT_MQTT", null);
        assertEquals("UNKNOWN", jdbc.lastArgs.get("RAW_INSERT")[5]);
    }

    @Test
    void anEventKeyThatIsAlreadyStoredIsNotStoredAgain() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerUpdate(0);                 // ON CONFLICT DO NOTHING
        assertFalse(new JdbcReadEventLog(jdbc).storeIfNew("EV-4", usbRead(), "CARD_READ", T, "EDGE_BATCH", null));
    }

    @Test
    void anUnmappedReadIsLoadedWithItsKeyReaderNumberTimeAndDevice() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerRows(Map.of(
                "event_id", "EV-5", "event_key", -7L, "reader_name", "DrC0F8", "credential_identifier", "38",
                "hardware_timestamp", T, "source_device", "USB-C0F8"));
        Optional<UnmappedRead> read = new JdbcReadEventLog(jdbc).findUnmapped("EV-5");

        assertEquals(Optional.of(new UnmappedRead(-7L, "DrC0F8", "38", T, "USB-C0F8")), read);
        assertEquals(List.of("UNMAPPED_ROW"), jdbc.calls);
        assertEquals("EV-5", jdbc.lastArgs.get("UNMAPPED_ROW")[0]);
    }

    @Test
    void noRowMeansNoUnmappedRead() {
        assertEquals(Optional.empty(), new JdbcReadEventLog(new ScriptedJdbc()).findUnmapped("EV-6"));
    }

    @Test
    void resolvingARowSetsItsCardAndHowItWasFound() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcReadEventLog(jdbc).markResolved("EV-7", "044E6602AD1490", "TAG_REGISTER");
        assertEquals(List.<Object>of("044E6602AD1490", "TAG_REGISTER", "EV-7"), List.of(jdbc.lastArgs.get("RESOLVE_ROW")));
    }

    @Test
    void theUnmappedListAndCountAskForLocalUnmappedReads() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("E1", "E2").answerCount(2);
        JdbcReadEventLog log = new JdbcReadEventLog(jdbc);
        assertEquals(List.of("E1", "E2"), log.unmappedIds(200));
        assertEquals(200, jdbc.lastArgs.get("UNMAPPED_IDS")[0]);
        assertEquals(2, log.countUnmapped());
    }
}
