package com.vinabeef.ingestion;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** How an event from Safetrust is classified and how the animal's tag id (the card UID) is read from it. */
class SafetrustEventDtoTest {

    @Test
    void theCardUidIsTrimmedAndUpperCase() {
        SafetrustEventDto dto = new SafetrustEventDto();
        dto.setCardUuid(" 044e6602ad1490 ");
        assertEquals("044E6602AD1490", dto.getCardUuid());
        assertEquals("CARD_READ", dto.getEventClass());
    }

    @Test
    void theCardUidFallsBackToTheDeviceSnapshot() {
        SafetrustEventDto dto = new SafetrustEventDto();
        dto.setDeviceSnapshot(Map.of("U_D_I_D", "044e6602ad1490"));
        assertEquals("044E6602AD1490", dto.getCardUuid());
    }

    @Test
    void aBlankCardUidIsNoCard() {
        SafetrustEventDto dto = new SafetrustEventDto();
        dto.setCardUuid("   ");
        assertNull(dto.getCardUuid());
    }

    @Test
    void readerConnectAndDisconnectAreReaderStatus() {
        SafetrustEventDto connect = new SafetrustEventDto();
        connect.setNotes(Map.of("ACTION", "READER_CONNECT_MQTT"));
        assertEquals("READER_STATUS", connect.getEventClass());

        SafetrustEventDto disconnect = new SafetrustEventDto();
        disconnect.setNotes(Map.of("ACTION", "READER_DISCONNECT_MQTT"));
        assertEquals("READER_STATUS", disconnect.getEventClass());
    }

    @Test
    void lidarRfInfoAndTemperatureAreReaderTelemetry() {
        SafetrustEventDto lidar = new SafetrustEventDto();
        lidar.setNotes(Map.of("ACTION", "LIDAR_ACTIVATED"));
        assertEquals("READER_TELEMETRY", lidar.getEventClass());

        SafetrustEventDto rf = new SafetrustEventDto();
        rf.setNotes(Map.of("ACTION", "EVT_RF_INFO_TRACKING"));
        assertEquals("READER_TELEMETRY", rf.getEventClass());

        SafetrustEventDto temperature = new SafetrustEventDto();
        temperature.setTrackingType("TEMPERATURE");
        assertEquals("READER_TELEMETRY", temperature.getEventClass());
        assertEquals("TEMPERATURE", temperature.getGateAction());
    }

    @Test
    void theReaderNameFallsBackToTheNotes() {
        SafetrustEventDto dto = new SafetrustEventDto();
        dto.setNotes(Map.of("READER_NAME", "DrC0F8"));
        assertEquals("DrC0F8", dto.getReaderName());
        dto.setReaderName("DrC1C4");
        assertEquals("DrC1C4", dto.getReaderName());
    }

    @Test
    void anOpenDoorEventKeepsItsActionAndDefaultsToPassageWhenThereIsNone() {
        SafetrustEventDto open = new SafetrustEventDto();
        open.setNotes(Map.of("ACTION", "OPEN_DOOR"));
        assertEquals("OPEN_DOOR", open.getGateAction());
        assertEquals("PASSAGE", new SafetrustEventDto().getGateAction());
    }
}
