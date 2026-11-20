-- V14: gate reader pairs (inner/outer), crossing state, full Safetrust event capture
--
-- Why this migration exists
--   * V3 and V7 both registered reader 'DrC0F8' (on GATE-DL-01C and GATE-DL-02A). Looking a gate up by
--     reader name then returned two rows and every read at that reader failed and rolled back.
--   * A barn gate is made of two readers (inner, outer). Direction comes from the ORDER in which the same
--     tag is seen by them, so the schema needs to know which reader is which.
--   * Only card reads were stored. Reader connect/disconnect, LiDAR and temperature events were dropped.

-- 1. One reader name belongs to exactly one gate ---------------------------------------------------------
-- Keep the oldest registration (V3 put DrC0F8 on GATE-DL-01C on purpose), detach the others.
UPDATE gates g
SET reader_name = NULL
WHERE g.reader_name IS NOT NULL
  AND EXISTS (SELECT 1
              FROM gates o
              WHERE o.reader_name = g.reader_name
                AND (COALESCE(o.created_at, 'epoch'::timestamptz), o.gate_id)
                    < (COALESCE(g.created_at, 'epoch'::timestamptz), g.gate_id));

CREATE UNIQUE INDEX IF NOT EXISTS uq_gates_reader_name
    ON gates (reader_name) WHERE reader_name IS NOT NULL;

-- 2. Pairing parameters per gate -------------------------------------------------------------------------
ALTER TABLE gates ADD COLUMN IF NOT EXISTS pair_window_ms INT NOT NULL DEFAULT 15000;     -- max gap between the two reads
ALTER TABLE gates ADD COLUMN IF NOT EXISTS repeat_debounce_ms INT NOT NULL DEFAULT 3000;  -- repeat read at the same reader

-- 3. Physical readers of a gate --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gate_readers (
    reader_name          VARCHAR(100) PRIMARY KEY,                       -- Safetrust READER_NAME, e.g. 'DrC0F8'
    gate_id              VARCHAR(36)  NOT NULL REFERENCES gates(gate_id) ON DELETE CASCADE,
    side                 VARCHAR(5)   NOT NULL CHECK (side IN ('INNER', 'OUTER')),
    safetrust_reader_key BIGINT UNIQUE,                                  -- Safetrust READER_KEY, e.g. 318567
    description          VARCHAR(120),                                   -- Safetrust READER_DESCRIPTION, e.g. 'Door C0F8'
    hardware_type        VARCHAR(16),                                    -- Safetrust HARDWARE_TYPE
    connection_status    VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN'
                         CHECK (connection_status IN ('ONLINE', 'OFFLINE', 'UNKNOWN')),
    last_ip              VARCHAR(45),                                    -- Safetrust IP_ADDRESS (sent on connect)
    last_seen_at         TIMESTAMP WITH TIME ZONE,                       -- hardware time of the newest event
    status_changed_at    TIMESTAMP WITH TIME ZONE,
    UNIQUE (gate_id, side)                                               -- one inner and one outer reader per gate
);

CREATE INDEX IF NOT EXISTS idx_gate_readers_gate ON gate_readers (gate_id);

-- 4. Unpaired sightings: a tag seen at one reader of a gate that has not been matched by the other reader yet
-- seen_ms is EVENT time (hardware TIMESTAMP), so replaying an edge backlog, in any arrival order, gives the
-- same answer as live ingestion.
CREATE TABLE IF NOT EXISTS gate_crossing_candidates (
    event_id VARCHAR(64) PRIMARY KEY REFERENCES rfid_read_events(event_id) ON DELETE CASCADE,
    tag_epc  VARCHAR(64) NOT NULL,
    gate_id  VARCHAR(36) NOT NULL REFERENCES gates(gate_id) ON DELETE CASCADE,
    side     VARCHAR(5)  NOT NULL CHECK (side IN ('INNER', 'OUTER')),
    seen_ms  BIGINT      NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_crossing_candidates_lookup ON gate_crossing_candidates (tag_epc, gate_id, seen_ms);

-- 5. Movements remember how the direction was decided ---------------------------------------------------
ALTER TABLE movements ADD COLUMN IF NOT EXISTS detection_method VARCHAR(20) NOT NULL DEFAULT 'GATE_MODE';  -- GATE_MODE | PAIRED_READERS
ALTER TABLE movements ADD COLUMN IF NOT EXISTS first_event_id VARCHAR(64) REFERENCES rfid_read_events(event_id) ON DELETE RESTRICT;
ALTER TABLE movements ADD COLUMN IF NOT EXISTS crossing_started_at TIMESTAMP WITH TIME ZONE;

-- 6. Raw event table: keep every Safetrust event and the fields that matter ------------------------------
ALTER TABLE rfid_read_events ALTER COLUMN company_key DROP NOT NULL;   -- temperature events carry no COMPANY_KEY
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS event_class VARCHAR(16) NOT NULL DEFAULT 'CARD_READ';
-- CARD_READ | READER_STATUS | READER_TELEMETRY | OTHER
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS reader_key BIGINT;             -- READER_KEY
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS user_key BIGINT;               -- USER_KEY (generated for the card)
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS building_access_key BIGINT;    -- BUILDING_ACCESS_KEY
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS credential_type VARCHAR(32);   -- e.g. CDT_HF_DESFIRE
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS sensor_type VARCHAR(20);       -- e.g. DOOR
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS cloud_modified_ms BIGINT;      -- MODIFIED, Safetrust cloud time
CREATE INDEX IF NOT EXISTS idx_read_events_reader_time ON rfid_read_events (reader_name, observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_read_events_class ON rfid_read_events (event_class, observed_at DESC);

-- Card identity: the card UID is stored upper case, the same way Safetrust sends CARD_UUID
UPDATE rfid_tags t
SET epc_value = upper(t.epc_value)
WHERE t.epc_value <> upper(t.epc_value)
  AND NOT EXISTS (SELECT 1 FROM rfid_tags o WHERE o.epc_value = upper(t.epc_value));

-- 7. Events that could not be processed are kept, not lost ----------------------------------------------
CREATE TABLE IF NOT EXISTS ingestion_dead_letters (
    dead_letter_id BIGSERIAL PRIMARY KEY,
    ingress_mode   VARCHAR(30)  NOT NULL,
    event_key      BIGINT,
    error_message  TEXT         NOT NULL,
    payload        TEXT         NOT NULL,
    received_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    replayed_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_dead_letters_open ON ingestion_dead_letters (received_at) WHERE replayed_at IS NULL;

-- 8. Register the real barn gate: DrC0F8 is the inner reader, DrC1C4 the outer reader of GATE-DL-01C -----
-- (reader numbers and descriptions are taken from live Safetrust events)
UPDATE gates SET reader_name = 'DrC0F8' WHERE gate_id = 'GATE-DL-01C' AND reader_name IS NULL
  AND NOT EXISTS (SELECT 1 FROM gates o WHERE o.reader_name = 'DrC0F8');

INSERT INTO gate_readers (reader_name, gate_id, side, safetrust_reader_key, description, hardware_type)
SELECT 'DrC0F8', 'GATE-DL-01C', 'INNER', 318567, 'Door C0F8', '23'
WHERE EXISTS (SELECT 1 FROM gates WHERE gate_id = 'GATE-DL-01C')
ON CONFLICT (reader_name) DO NOTHING;

INSERT INTO gate_readers (reader_name, gate_id, side, safetrust_reader_key, description, hardware_type)
SELECT 'DrC1C4', 'GATE-DL-01C', 'OUTER', 318617, 'Door C1C4', '23'
WHERE EXISTS (SELECT 1 FROM gates WHERE gate_id = 'GATE-DL-01C')
ON CONFLICT (reader_name) DO NOTHING;
