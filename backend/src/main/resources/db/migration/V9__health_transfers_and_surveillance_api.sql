-- 1. Extend animals with clinical health status & quarantine notes
ALTER TABLE animals ADD COLUMN IF NOT EXISTS health_status VARCHAR(30) DEFAULT 'HEALTHY';
ALTER TABLE animals ADD COLUMN IF NOT EXISTS quarantine_notes TEXT;

-- 2. Longitudinal Barn & Farm Transfer Ledger
CREATE TABLE IF NOT EXISTS animal_transfers (
    transfer_id VARCHAR(64) PRIMARY KEY,
    animal_id VARCHAR(64) NOT NULL REFERENCES animals(animal_id),
    from_barn_id VARCHAR(64) REFERENCES barns(barn_id),
    from_farm_id VARCHAR(64) REFERENCES farms(farm_id),
    to_barn_id VARCHAR(64) NOT NULL REFERENCES barns(barn_id),
    to_farm_id VARCHAR(64) REFERENCES farms(farm_id),
    transfer_type VARCHAR(30) NOT NULL, -- INTER_BARN or INTER_FARM
    reason TEXT,
    approved_by VARCHAR(100),
    transferred_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. External Regulatory API Transmission Log (e.g., National Animal Disease Control System)
CREATE TABLE IF NOT EXISTS external_transmissions (
    transmission_id VARCHAR(64) PRIMARY KEY,
    target_system VARCHAR(120) NOT NULL,
    endpoint_url TEXT NOT NULL,
    report_type VARCHAR(60) NOT NULL,
    farm_scope VARCHAR(64) NOT NULL,
    record_count INT NOT NULL,
    payload_format VARCHAR(20) NOT NULL,
    http_status INT NOT NULL,
    response_summary TEXT,
    payload_snapshot TEXT,
    transmitted_by VARCHAR(100),
    transmitted_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 4. Indexes for longitudinal history queries at 25,000+ cattle scale
CREATE INDEX IF NOT EXISTS idx_health_records_animal_event ON health_records(animal_id, event_at DESC);
CREATE INDEX IF NOT EXISTS idx_animal_transfers_animal_time ON animal_transfers(animal_id, transferred_at DESC);
CREATE INDEX IF NOT EXISTS idx_movements_animal_time ON movements(animal_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_animals_health_status ON animals(health_status);

-- 5. Seed sample clinical treatments, quarantine status, transfers, and NADCS transmission log
UPDATE animals SET health_status = 'UNDER_TREATMENT', quarantine_notes = 'Mild hoof inflammation - topical antibiotic course'
WHERE animal_id = 'COW-0002';

INSERT INTO health_records (health_id, animal_id, record_type, administered_by, payload, event_at) VALUES
('HLT-TRT-001', 'COW-0002', 'TREATMENT', 'Dr. Nguyen Van A',
 jsonb_build_object('condition', 'Pododermatitis (Foot Rot)', 'medication', 'Oxytetracycline 200mg/ml', 'dosage', '10ml IM', 'outcome', 'Responding well'),
 CURRENT_TIMESTAMP - INTERVAL '3 days'),
('HLT-CHK-002', 'CI 356278', 'HEALTH_CHECK', 'Dr. Le Thi B',
 jsonb_build_object('temperature_c', '38.6', 'heart_rate_bpm', '64', 'body_condition_score', '4.0/5.0', 'status', 'Cleared for grazing'),
 CURRENT_TIMESTAMP - INTERVAL '1 day')
ON CONFLICT (health_id) DO NOTHING;

INSERT INTO animal_transfers (transfer_id, animal_id, from_barn_id, from_farm_id, to_barn_id, to_farm_id, transfer_type, reason, approved_by, transferred_at) VALUES
('TRF-INIT-001', 'CI 356278', 'BARN-GL-001', 'FARM-GIA-LAI', 'BARN-DL-01', 'FARM-DAK-LAK', 'INTER_FARM',
 'Breeding & finishing rotation to Dak Lak Station', 'manager_daklak', CURRENT_TIMESTAMP - INTERVAL '21 days'),
('TRF-INIT-002', 'COW-0003', 'BARN-DL-01', 'FARM-DAK-LAK', 'BARN-DL-02', 'FARM-DAK-LAK', 'INTER_BARN',
 'Feed group weight reallocation', 'supervisor_barn1', CURRENT_TIMESTAMP - INTERVAL '7 days')
ON CONFLICT (transfer_id) DO NOTHING;

INSERT INTO external_transmissions (transmission_id, target_system, endpoint_url, report_type, farm_scope, record_count, payload_format, http_status, response_summary, payload_snapshot, transmitted_by, transmitted_at) VALUES
('TX-NADCS-202601', 'National Animal Disease Control System (NADCS)', 'https://api.nadcs.dah.gov.vn/v2/surveillance/herd-health',
 'OUTBREAK_SURVEILLANCE', 'ALL', 25003, 'JSON', 202,
 'ACCEPTED: Surveillance batch queued by National Veterinary Epidemiology Center (Receipt #VN-DAH-88412)',
 '{"agency":"Department of Animal Health (DAH)","schema":"NADCS-2.1","totalHerd":25003,"vaccinatedCount":20002,"underTreatment":1,"quarantined":0}',
 'admin', CURRENT_TIMESTAMP - INTERVAL '6 hours')
ON CONFLICT (transmission_id) DO NOTHING;