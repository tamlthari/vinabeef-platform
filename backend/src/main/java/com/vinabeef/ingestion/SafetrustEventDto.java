package com.vinabeef.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;

/**
 * One event published by Safetrust on {@code Web/<company>/Monitoring/<device>}.
 *
 * <p>Card reads carry {@code CARD_UUID} (the card UID, which is the animal's tag), {@code READER_NAME} (the
 * reader, for example {@code DrC0F8}) and {@code TIMESTAMP} (reader time in epoch milliseconds). Other events
 * come on the same topic and have no card: reader connect/disconnect, LiDAR and temperature reports.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SafetrustEventDto {
    @JsonProperty("EVENT_KEY")
    private Long eventKey;

    @JsonProperty("TRANSACTION_ID")
    private Long transactionId;

    /** Safetrust DEVICE_KEY. For a card read this identifies the card's device record, not the reader. */
    @JsonProperty("DEVICE_KEY")
    private String deviceKey;

    @JsonProperty("COMPANY_KEY")
    private String companyKey;

    @JsonProperty("READER_NAME")
    private String readerName;

    @JsonProperty("READER_KEY")
    private Long readerKey;

    @JsonProperty("CARD_UUID")
    private String cardUuid;

    @JsonProperty("CREDENTIAL_IDENTIFIER")
    private String credentialIdentifier;

    @JsonProperty("CREDENTIAL_TYPE")
    private String credentialType;

    @JsonProperty("USER_KEY")
    private Long userKey;

    @JsonProperty("BUILDING_ACCESS_KEY")
    private Long buildingAccessKey;

    @JsonProperty("SENSOR_TYPE")
    private String sensorType;

    /** Reader time, epoch milliseconds. */
    @JsonProperty("TIMESTAMP")
    private Long timestamp;

    /** Safetrust cloud processing time, epoch milliseconds. */
    @JsonProperty("MODIFIED")
    private Long modified;

    /** Only sent with reader connect events. */
    @JsonProperty("IP_ADDRESS")
    private String ipAddress;

    @JsonProperty("TRACKING_TYPE")
    private String trackingType;

    @JsonProperty("DEVICE_SNAPSHOT")
    private Map<String, Object> deviceSnapshot;

    @JsonProperty("NOTES")
    private Map<String, Object> notes;

    @JsonProperty("rawPayload")
    private String rawPayload;

    /**
     * Only a barn edge gateway sets these three, for a read from a USB reader attached to the gateway (the route that
     * works without Internet). The names carry the {@code EDGE_} prefix so a Safetrust event can never be mistaken for one.
     * {@code EDGE_SOURCE} is {@code LOCAL_USB}; {@code EDGE_DEVICE} names the USB device; {@code EDGE_TIME_SOURCE} says
     * where {@code TIMESTAMP} came from ({@code GATEWAY_CLOCK}: the gateway, because the USB reader sends no time).
     */
    @JsonProperty("EDGE_SOURCE")
    private String edgeSource;

    @JsonProperty("EDGE_DEVICE")
    private String edgeDevice;

    @JsonProperty("EDGE_TIME_SOURCE")
    private String edgeTimeSource;

    public static final String SOURCE_SAFETRUST = "SAFETRUST";
    public static final String SOURCE_LOCAL_USB = "LOCAL_USB";
    public static final String CLASS_LOCAL_UNMAPPED = "LOCAL_UNMAPPED";

    /** True for a read that a barn edge gateway took from its own USB reader. */
    @JsonIgnore
    public boolean isLocalUsb() {
        return SOURCE_LOCAL_USB.equals(edgeSource);
    }

    /** SAFETRUST or LOCAL_USB: the route this read came by. */
    @JsonIgnore
    public String getReadSource() {
        return isLocalUsb() ? SOURCE_LOCAL_USB : SOURCE_SAFETRUST;
    }

    /** The credential number without blanks, or null. */
    @JsonIgnore
    public String getCredentialNumber() {
        return credentialIdentifier == null || credentialIdentifier.isBlank() ? null : credentialIdentifier.trim();
    }

    /** The card UID is kept upper case and trimmed, the way Safetrust sends CARD_UUID. */
    public void setCardUuid(String value) {
        this.cardUuid = value == null ? null : value.trim().toUpperCase();
    }

    /** CARD_UUID, or the UID inside DEVICE_SNAPSHOT when CARD_UUID is missing. Upper case, or null. */
    public String getCardUuid() {
        if (cardUuid != null && !cardUuid.isBlank()) {
            return cardUuid;
        }
        if (deviceSnapshot != null && deviceSnapshot.get("U_D_I_D") != null) {
            String udid = String.valueOf(deviceSnapshot.get("U_D_I_D")).trim();
            return udid.isEmpty() ? null : udid.toUpperCase();
        }
        return null;
    }

    /** READER_NAME, or NOTES.READER_NAME when the top level field is missing. */
    public String getReaderName() {
        if (readerName != null && !readerName.isBlank()) {
            return readerName;
        }
        if (notes != null && notes.get("READER_NAME") != null) {
            return String.valueOf(notes.get("READER_NAME"));
        }
        return null;
    }

    public String getUserName() {
        if (notes != null && notes.containsKey("USER_NAME")) {
            return String.valueOf(notes.get("USER_NAME"));
        }
        return null;
    }

    /** NOTES.ACTION exactly as sent (for example OPEN_DOOR, LIDAR_ACTIVATED), or null. */
    public String getRawAction() {
        if (notes != null && notes.get("ACTION") != null) {
            return String.valueOf(notes.get("ACTION"));
        }
        return null;
    }

    /** NOTES.ACTION, else the tracking type (for example TEMPERATURE), else PASSAGE. */
    public String getGateAction() {
        String action = getRawAction();
        if (action != null) {
            return action;
        }
        if (trackingType != null && !trackingType.isBlank()) {
            return trackingType;
        }
        return "PASSAGE";
    }

    /**
     * CARD_READ when a card is present, LOCAL_UNMAPPED for a USB read whose credential number maps to no card, READER_STATUS
     * for reader connect/disconnect, READER_TELEMETRY for LiDAR, RF info and temperature reports, otherwise OTHER.
     */
    public String getEventClass() {
        if (getCardUuid() != null) {
            return "CARD_READ";
        }
        if (isLocalUsb() && getCredentialNumber() != null) {
            return CLASS_LOCAL_UNMAPPED;      // a USB read whose credential number maps to no card (yet)
        }
        String action = getRawAction();
        if ("READER_CONNECT_MQTT".equals(action) || "READER_DISCONNECT_MQTT".equals(action)) {
            return "READER_STATUS";
        }
        if (trackingType != null || "LIDAR_ACTIVATED".equals(action) || "EVT_RF_INFO_TRACKING".equals(action)) {
            return "READER_TELEMETRY";
        }
        return "OTHER";
    }
}
