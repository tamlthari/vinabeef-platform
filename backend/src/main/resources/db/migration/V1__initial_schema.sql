-- 1. Facility Hierarchy
CREATE TABLE farms (
    farm_id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    location VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE barns (
    barn_id VARCHAR(36) PRIMARY KEY,
    farm_id VARCHAR(36) NOT NULL REFERENCES farms(farm_id) ON DELETE RESTRICT,
    code VARCHAR(50) NOT NULL,
    company_key VARCHAR(64), -- Maps to Safetrust COMPANY_KEY (e.g., '501')
    capacity INT NOT NULL,
    current_headcount INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (farm_id, code)
);

CREATE INDEX idx_barns_company_key ON barns(company_key);

CREATE TABLE gates (
    gate_id VARCHAR(36) PRIMARY KEY,
    barn_id VARCHAR(36) NOT NULL REFERENCES barns(barn_id) ON DELETE RESTRICT,
    label VARCHAR(100) NOT NULL,
    reader_name VARCHAR(100), -- Maps to Safetrust READER_NAME (e.g., 'DrEBE6')
    direction_mode VARCHAR(20) DEFAULT 'BIDIRECTIONAL',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_gates_reader_name ON gates(reader_name);

-- 2. Animal & RFID Identity Registry
CREATE TABLE animals (
    animal_id VARCHAR(64) PRIMARY KEY, -- Supports Safetrust USER_NAME (e.g., 'CI 356278') or internal UUID
    assigned_barn_id VARCHAR(36) NOT NULL REFERENCES barns(barn_id) ON DELETE RESTRICT,
    lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    registered_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_animals_assigned_barn ON animals(assigned_barn_id);

CREATE TABLE rfid_tags (
    tag_id VARCHAR(64) PRIMARY KEY,
    epc_value VARCHAR(64) UNIQUE NOT NULL, -- Maps to Safetrust CARD_UUID (e.g., '044E6602AD1490')
    credential_identifier VARCHAR(64),     -- Maps to Safetrust CREDENTIAL_IDENTIFIER (e.g., '8')
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tag_assignments (
    assignment_id VARCHAR(64) PRIMARY KEY,
    animal_id VARCHAR(64) NOT NULL REFERENCES animals(animal_id) ON DELETE RESTRICT,
    tag_id VARCHAR(64) NOT NULL REFERENCES rfid_tags(tag_id) ON DELETE RESTRICT,
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_tag_assignments_active ON tag_assignments(tag_id, valid_to);

-- 3. Raw IoT Ingress (ADR-002: Raw Observation Evidence)
CREATE TABLE rfid_read_events (
    event_id VARCHAR(64) PRIMARY KEY,
    event_key BIGINT UNIQUE,                   -- Hardware idempotency key (Safetrust EVENT_KEY)
    transaction_id BIGINT,                      -- Safetrust TRANSACTION_ID
    device_key VARCHAR(64),                     -- Edge gateway/sensor key (Safetrust DEVICE_KEY)
    company_key VARCHAR(64) NOT NULL,           -- Facility key (Safetrust COMPANY_KEY)
    reader_name VARCHAR(100) NOT NULL,          -- Gate reader name (Safetrust READER_NAME)
    card_uuid VARCHAR(64) NOT NULL,             -- Tag EPC/UUID (Safetrust CARD_UUID)
    credential_identifier VARCHAR(64),          -- Credential number
    user_name VARCHAR(100),                     -- Cattle label/code (Safetrust USER_NAME)
    gate_action VARCHAR(50),                    -- Safetrust NOTES.ACTION (e.g., 'OPEN_DOOR')
    hardware_timestamp BIGINT NOT NULL,         -- Sensor epoch milliseconds
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    raw_payload JSONB NOT NULL DEFAULT '{}',    -- Immutable audit payload
    received_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_read_events_observed ON rfid_read_events(observed_at DESC);
CREATE INDEX idx_read_events_card_uuid ON rfid_read_events(card_uuid);

-- 4. Accepted Movements & Current Presence Projections (ADR-002 & ADR-003)
CREATE TABLE movements (
    movement_id VARCHAR(64) PRIMARY KEY,
    animal_id VARCHAR(64) NOT NULL REFERENCES animals(animal_id) ON DELETE RESTRICT,
    gate_id VARCHAR(36) NOT NULL REFERENCES gates(gate_id) ON DELETE RESTRICT,
    source_event_id VARCHAR(64) NOT NULL REFERENCES rfid_read_events(event_id) ON DELETE RESTRICT,
    direction VARCHAR(10) NOT NULL,             -- 'IN' or 'OUT'
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_movements_animal ON movements(animal_id, occurred_at DESC);
CREATE INDEX idx_movements_gate ON movements(gate_id, occurred_at DESC);

CREATE TABLE presence_states (
    animal_id VARCHAR(64) PRIMARY KEY REFERENCES animals(animal_id) ON DELETE RESTRICT,
    current_barn_id VARCHAR(36) NOT NULL REFERENCES barns(barn_id) ON DELETE RESTRICT,
    presence_status VARCHAR(20) NOT NULL,       -- 'IN_BARN', 'OUT_PASTURE', 'UNCERTAIN'
    last_movement_id VARCHAR(64) REFERENCES movements(movement_id) ON DELETE SET NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 5. Longitudinal Health & Medical Ledger
CREATE TABLE health_records (
    health_id VARCHAR(64) PRIMARY KEY,
    animal_id VARCHAR(64) NOT NULL REFERENCES animals(animal_id) ON DELETE RESTRICT,
    record_type VARCHAR(50) NOT NULL,           -- 'VACCINATION', 'TREATMENT', 'DIAGNOSIS'
    administered_by VARCHAR(100),
    payload JSONB NOT NULL DEFAULT '{}',
    event_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_health_animal ON health_records(animal_id, event_at DESC);

-- 6. Anomaly Alerting & Discrepancies
CREATE TABLE alerts (
    alert_id VARCHAR(64) PRIMARY KEY,
    type VARCHAR(50) NOT NULL,                   -- 'MISSING', 'UNEXPECTED_TAG', 'WRONG_BARN'
    animal_id VARCHAR(64) REFERENCES animals(animal_id) ON DELETE SET NULL,
    barn_id VARCHAR(36) NOT NULL REFERENCES barns(barn_id) ON DELETE RESTRICT,
    state VARCHAR(20) NOT NULL DEFAULT 'OPEN',   -- 'OPEN', 'ACKNOWLEDGED', 'RESOLVED'
    evidence JSONB NOT NULL DEFAULT '{}',
    raised_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_alerts_barn_state ON alerts(barn_id, state);

-- 7. Tamper-Resistant Audit Trail (ADR-006)
CREATE TABLE audit_entries (
    audit_id VARCHAR(64) PRIMARY KEY,
    actor_id VARCHAR(100) NOT NULL,
    action VARCHAR(100) NOT NULL,
    target VARCHAR(100) NOT NULL,
    outcome VARCHAR(20) NOT NULL,                -- 'SUCCESS', 'DENIED', 'FAILED'
    details JSONB DEFAULT '{}',
    event_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_event_at ON audit_entries(event_at DESC);