-- Seed baseline vaccination records for cattle in Dak Lak
INSERT INTO health_records (health_id, animal_id, record_type, administered_by, payload, event_at) VALUES
('HLT-VAC-01', 'CI 356278', 'VACCINATION', 'Dr. Nguyen Van A', '{"vaccine": "Foot-and-Mouth Disease (FMD)", "batch": "FMD-2026-X1"}'::jsonb, CURRENT_TIMESTAMP - INTERVAL '14 days'),
('HLT-VAC-02', 'COW-0002', 'VACCINATION', 'Dr. Nguyen Van A', '{"vaccine": "Foot-and-Mouth Disease (FMD)", "batch": "FMD-2026-X1"}'::jsonb, CURRENT_TIMESTAMP - INTERVAL '10 days'),
('HLT-VAC-03', 'CI 356278', 'VACCINATION', 'Dr. Le Thi B', '{"vaccine": "Bovine Rhinotracheitis (IBR)", "batch": "IBR-2026-V4"}'::jsonb, CURRENT_TIMESTAMP - INTERVAL '2 days')
ON CONFLICT (health_id) DO NOTHING;