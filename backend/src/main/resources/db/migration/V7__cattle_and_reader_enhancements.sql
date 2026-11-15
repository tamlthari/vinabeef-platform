-- 1. Extend animals table with breed and registration timestamp
ALTER TABLE animals ADD COLUMN IF NOT EXISTS breed VARCHAR(80) DEFAULT 'Brahman Cross';
ALTER TABLE animals ADD COLUMN IF NOT EXISTS weight_kg NUMERIC(6,2) DEFAULT 420.00;
ALTER TABLE animals ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

-- 2. Support historical tag reassignment via valid_to
ALTER TABLE tag_assignments ADD COLUMN IF NOT EXISTS valid_to TIMESTAMP WITH TIME ZONE;

-- 3. Extend gates table with firmware version & operational status
ALTER TABLE gates ADD COLUMN IF NOT EXISTS firmware_version VARCHAR(32) DEFAULT '1.58.1998';
ALTER TABLE gates ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'ONLINE';

-- 4. Ensure sample cattle & gate readers exist for immediate UI preview
INSERT INTO animals (animal_id, assigned_barn_id, lifecycle_status, breed, weight_kg) VALUES
('CI 356278', 'BARN-DL-01', 'ACTIVE', 'Wagyu Cross', 485.50),
('COW-0002',  'BARN-DL-01', 'ACTIVE', 'Brahman', 430.00),
('COW-0003',  'BARN-DL-02', 'ACTIVE', 'Angus', 510.20)
ON CONFLICT (animal_id) DO NOTHING;

INSERT INTO gates (gate_id, barn_id, label, reader_name, direction_mode, firmware_version, status) VALUES
('GATE-DL-01A', 'BARN-DL-01', 'North Pasture Gate', 'DrEBE6', 'BIDIRECTIONAL', '1.58.1998', 'ONLINE'),
('GATE-DL-01C', 'BARN-DL-01', 'East Grazing Gate',  'Dr49F9', 'BIDIRECTIONAL', '1.58.1998', 'ONLINE'),
('GATE-DL-02A', 'BARN-DL-02', 'West Feeding Gate',  'DrC0F8', 'BIDIRECTIONAL', '1.57.1998', 'ONLINE')
ON CONFLICT (gate_id) DO NOTHING;