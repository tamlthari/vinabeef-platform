-- 1. Extend alerts table for enterprise triage & resolution workflow
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS severity VARCHAR(20) DEFAULT 'HIGH';
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS acknowledged_by VARCHAR(100);
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS acknowledged_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS resolved_by VARCHAR(100);
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS resolution_note TEXT;

-- 2. Indexes for fast alert queries and missing-cattle roll-call scans at 25k scale
CREATE INDEX IF NOT EXISTS idx_alerts_state_raised ON alerts(state, raised_at DESC);
CREATE INDEX IF NOT EXISTS idx_alerts_type_state ON alerts(type, state);
CREATE INDEX IF NOT EXISTS idx_alerts_barn_id ON alerts(barn_id);
CREATE INDEX IF NOT EXISTS idx_presence_barn_status ON presence_states(current_barn_id, presence_status);

-- 3. Seed representative open alerts for immediate verification
INSERT INTO alerts (alert_id, type, severity, animal_id, barn_id, state, evidence, raised_at) VALUES
('ALT-INIT-001', 'UNEXPECTED_TAG', 'HIGH', NULL, 'BARN-DL-01', 'OPEN',
 jsonb_build_object('tagEpc', '044E6602AD1499', 'readerName', 'Dr49F9', 'gateId', 'GATE-DL-01C', 'reason', 'Unregistered RFID tag scanned at barn gate'),
 CURRENT_TIMESTAMP - INTERVAL '18 minutes'),
('ALT-INIT-002', 'WRONG_BARN', 'MEDIUM', 'CI 356278', 'BARN-DL-02', 'OPEN',
 jsonb_build_object('assignedBarn', 'BARN-DL-01', 'detectedBarn', 'BARN-DL-02', 'readerName', 'DrC0F8', 'direction', 'IN'),
 CURRENT_TIMESTAMP - INTERVAL '9 minutes'),
('ALT-INIT-003', 'MISSING_CATTLE', 'HIGH', 'COW-0003', 'BARN-DL-02', 'OPEN',
 jsonb_build_object('expectedBarn', 'BARN-DL-02', 'lastStatus', 'OUT_PASTURE', 'overdueMinutes', 45),
 CURRENT_TIMESTAMP - INTERVAL '3 minutes')
ON CONFLICT (alert_id) DO NOTHING;