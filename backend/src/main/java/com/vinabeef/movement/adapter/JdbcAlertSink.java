package com.vinabeef.movement.adapter;

import com.vinabeef.movement.port.AlertSink;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** {@link AlertSink} that writes rows of the {@code alerts} table in the caller's transaction. */
@Component
@RequiredArgsConstructor
public class JdbcAlertSink implements AlertSink {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void raiseUnexpectedTag(String barnId, String tagEpc, String userName, String readerName, String gateId, long eventKey) {
        jdbcTemplate.update("""
            INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
            VALUES (?, 'UNEXPECTED_TAG', 'HIGH', NULL, ?, 'OPEN',
                    jsonb_build_object('tagEpc', ?, 'userName', ?, 'readerName', ?, 'gateId', ?, 'eventKey', ?),
                    CURRENT_TIMESTAMP)
        """, "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                barnId, tagEpc, userName, readerName, gateId, eventKey);
    }

    @Override
    public void raiseUnmappedCredential(String barnId, String credential, String readerName, String gateId, long eventKey) {
        jdbcTemplate.update("""
            INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
            VALUES (?, 'UNEXPECTED_TAG', 'MEDIUM', NULL, ?, 'OPEN',
                    jsonb_build_object('credential', ?, 'readerName', ?, 'gateId', ?, 'eventKey', ?,
                                       'reason', 'Credential number from a USB reader that matches no known card'),
                    CURRENT_TIMESTAMP)
        """, "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                barnId, credential, readerName, gateId, eventKey);
    }

    @Override
    public void raiseWrongBarn(String animalId, String assignedBarn, String detectedBarn, String readerName, String direction) {
        jdbcTemplate.update("""
            INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
            VALUES (?, 'WRONG_BARN', 'MEDIUM', ?, ?, 'OPEN',
                    jsonb_build_object('assignedBarn', ?, 'detectedBarn', ?, 'readerName', ?, 'direction', ?),
                    CURRENT_TIMESTAMP)
        """, "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                animalId, detectedBarn, assignedBarn, detectedBarn, readerName, direction);
    }

    @Override
    public void raiseUnregisteredReader(String readerName, String cardUuid, long eventKey) {
        jdbcTemplate.update("""
            INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at)
            VALUES (?, 'UNEXPECTED_TAG', 'MEDIUM', NULL, 'BARN-DL-01', 'OPEN',
                    jsonb_build_object('readerName', ?, 'tagEpc', ?, 'eventKey', ?, 'reason', 'Unregistered gate reader'),
                    CURRENT_TIMESTAMP)
        """, "ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                readerName, cardUuid, eventKey);
    }

    @Override
    public void resolveMissingCattle(String animalId, String readerName) {
        jdbcTemplate.update("""
                UPDATE alerts
                SET state = 'RESOLVED',
                    resolved_by = 'SYSTEM_AUTO',
                    resolved_at = CURRENT_TIMESTAMP,
                    resolution_note = 'Animal detected returning IN via gate ' || ?
                WHERE animal_id = ? AND type = 'MISSING_CATTLE' AND state IN ('OPEN', 'ACKNOWLEDGED')
            """, readerName, animalId);
    }
}
