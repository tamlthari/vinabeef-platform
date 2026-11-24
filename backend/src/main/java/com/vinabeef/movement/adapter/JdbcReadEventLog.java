package com.vinabeef.movement.adapter;

import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.movement.port.ReadEventLog;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link ReadEventLog} over {@code rfid_read_events}. */
@Component
@RequiredArgsConstructor
public class JdbcReadEventLog implements ReadEventLog {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean storeIfNew(String eventId, SafetrustEventDto dto, String eventClass,
                              long hardwareMs, String ingressMode, String cardResolvedBy) {
        String payload = dto.getRawPayload() != null && !dto.getRawPayload().isBlank() ? dto.getRawPayload() : "{}";
        String readerName = dto.getReaderName() != null ? dto.getReaderName() : "UNKNOWN";
        int rows = jdbcTemplate.update("""
                INSERT INTO rfid_read_events (
                    event_id, event_key, transaction_id, device_key, company_key, reader_name, card_uuid,
                    credential_identifier, user_name, gate_action, hardware_timestamp, observed_at, raw_payload,
                    ingress_source, event_class, reader_key, user_key, building_access_key, credential_type,
                    sensor_type, cloud_modified_ms, read_source, source_device, time_source, card_resolved_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_key) DO NOTHING
                """,
                eventId, dto.getEventKey(), dto.getTransactionId(), dto.getDeviceKey(), dto.getCompanyKey(),
                readerName, dto.getCardUuid(), dto.getCredentialIdentifier(), dto.getUserName(),
                dto.getGateAction(), hardwareMs, Timestamp.from(Instant.ofEpochMilli(hardwareMs)), payload,
                ingressMode, eventClass, dto.getReaderKey(), dto.getUserKey(), dto.getBuildingAccessKey(),
                dto.getCredentialType(), dto.getSensorType(), dto.getModified(),
                dto.getReadSource(), dto.getEdgeDevice(), dto.getEdgeTimeSource(), cardResolvedBy);
        return rows == 1;
    }

    @Override
    public Optional<UnmappedRead> findUnmapped(String eventId) {
        LocalReadSql.Query load = LocalReadSql.unmappedRow(eventId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(load.sql(), load.params().toArray());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new UnmappedRead(
                row.get("event_key") == null ? null : ((Number) row.get("event_key")).longValue(),
                String.valueOf(row.get("reader_name")),
                row.get("credential_identifier") == null ? null : String.valueOf(row.get("credential_identifier")),
                ((Number) row.get("hardware_timestamp")).longValue(),
                row.get("source_device") == null ? null : String.valueOf(row.get("source_device"))));
    }

    @Override
    public void markResolved(String eventId, String cardUuid, String resolvedBy) {
        LocalReadSql.Query resolve = LocalReadSql.resolveRow(eventId, cardUuid, resolvedBy);
        jdbcTemplate.update(resolve.sql(), resolve.params().toArray());
    }

    @Override
    public List<String> unmappedIds(int limit) {
        LocalReadSql.Query q = LocalReadSql.unmappedIds(limit);
        return jdbcTemplate.queryForList(q.sql(), String.class, q.params().toArray());
    }

    @Override
    public int countUnmapped() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rfid_read_events WHERE event_class = 'LOCAL_UNMAPPED'", Integer.class);
        return n == null ? 0 : n;
    }
}
