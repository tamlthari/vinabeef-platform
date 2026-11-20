package com.vinabeef.ingestion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "rfid_read_events")
@Getter
@Setter
public class RfidReadEvent {
    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_key", unique = true)
    private Long eventKey;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "device_key")
    private String deviceKey;

    // Temperature and similar events carry no COMPANY_KEY (nullable since V14).
    @Column(name = "company_key")
    private String companyKey;

    @Column(name = "reader_name", nullable = false)
    private String readerName;

    // CHANGED: Removed nullable = false
    @Column(name = "card_uuid")
    private String cardUuid;

    @Column(name = "credential_identifier")
    private String credentialIdentifier;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "gate_action")
    private String gateAction;

    @Column(name = "hardware_timestamp", nullable = false)
    private Long hardwareTimestamp;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "received_at")
    private Instant receivedAt = Instant.now();

    @Column(name = "ingress_source")
    private String ingressSource;

    /** CARD_READ, READER_STATUS, READER_TELEMETRY or OTHER. */
    @Column(name = "event_class", nullable = false)
    private String eventClass = "CARD_READ";

    @Column(name = "reader_key")
    private Long readerKey;

    @Column(name = "user_key")
    private Long userKey;

    @Column(name = "building_access_key")
    private Long buildingAccessKey;

    @Column(name = "credential_type")
    private String credentialType;

    @Column(name = "sensor_type")
    private String sensorType;

    @Column(name = "cloud_modified_ms")
    private Long cloudModifiedMs;
}