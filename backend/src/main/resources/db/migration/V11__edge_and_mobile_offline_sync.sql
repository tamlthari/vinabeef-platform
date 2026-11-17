-- 1. Idempotency & device provenance for offline-first mobile clinical records
ALTER TABLE health_records ADD COLUMN IF NOT EXISTS client_event_id VARCHAR(80);
ALTER TABLE health_records ADD COLUMN IF NOT EXISTS device_id VARCHAR(80);

CREATE UNIQUE INDEX IF NOT EXISTS uq_health_records_client_event_id
    ON health_records(client_event_id)
    WHERE client_event_id IS NOT NULL;

-- 2. Track telemetry ingress source (LIVE_MQTT vs EDGE_BATCH)
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS ingress_source VARCHAR(30) DEFAULT 'LIVE_MQTT';