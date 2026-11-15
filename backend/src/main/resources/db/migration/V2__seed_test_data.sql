-- 1. Three Geographically Distributed Farms
INSERT INTO farms (farm_id, name, location) VALUES
('FARM-DAK-LAK', 'Dak Lak Cattle Station', 'Dak Lak Province'),
('FARM-GIA-LAI', 'Gia Lai Grazing Facility', 'Gia Lai Province'),
('FARM-DONG-NAI', 'Dong Nai Feedlot & Barns', 'Dong Nai Province')
ON CONFLICT (farm_id) DO NOTHING;

-- 2. Barns (Linking company_key '501' from Safetrust demo to Dak Lak Barn 01)
INSERT INTO barns (barn_id, farm_id, code, company_key, capacity, current_headcount) VALUES
('BARN-DL-01', 'FARM-DAK-LAK', 'BARN-01', '501', 250, 3),
('BARN-DL-02', 'FARM-DAK-LAK', 'BARN-02', '502', 200, 0),
('BARN-GL-01', 'FARM-GIA-LAI', 'BARN-01', '503', 300, 0),
('BARN-DN-01', 'FARM-DONG-NAI', 'BARN-01', '504', 150, 0)
ON CONFLICT (barn_id) DO NOTHING;

-- 3. Gates (Linking reader_name 'DrEBE6' from Safetrust demo to North Gate)
INSERT INTO gates (gate_id, barn_id, label, reader_name, direction_mode) VALUES
('GATE-DL-01A', 'BARN-DL-01', 'North Pasture Gate', 'DrEBE6', 'BIDIRECTIONAL'),
('GATE-DL-01B', 'BARN-DL-01', 'South Feeding Gate', 'DrEBE7', 'BIDIRECTIONAL')
ON CONFLICT (gate_id) DO NOTHING;

-- 4. RFID Tags (Including Safetrust demo tag 044E6602AD1490)
INSERT INTO rfid_tags (tag_id, epc_value, credential_identifier, status) VALUES
('TAG-SAFE-01', '044E6602AD1490', '8', 'ACTIVE'),
('TAG-DL-0002', 'E28011606000020498760002', '2', 'ACTIVE'),
('TAG-DL-0003', 'E28011606000020498760003', '3', 'ACTIVE')
ON CONFLICT (epc_value) DO NOTHING;

-- 5. Cattle Profiles (Mapping 'CI 356278' from Safetrust USER_NAME)
INSERT INTO animals (animal_id, assigned_barn_id, lifecycle_status) VALUES
('CI 356278', 'BARN-DL-01', 'ACTIVE'),
('COW-0002', 'BARN-DL-01', 'ACTIVE'),
('COW-0003', 'BARN-DL-01', 'ACTIVE')
ON CONFLICT (animal_id) DO NOTHING;

-- 6. Tag Assignments
INSERT INTO tag_assignments (assignment_id, animal_id, tag_id, valid_from) VALUES
('ASG-SAFE-01', 'CI 356278', 'TAG-SAFE-01', CURRENT_TIMESTAMP),
('ASG-DL-0002', 'COW-0002', 'TAG-DL-0002', CURRENT_TIMESTAMP),
('ASG-DL-0003', 'COW-0003', 'TAG-DL-0003', CURRENT_TIMESTAMP)
ON CONFLICT (assignment_id) DO NOTHING;

-- 7. Initial Presence State (3 cattle inside BARN-DL-01)
INSERT INTO presence_states (animal_id, current_barn_id, presence_status) VALUES
('CI 356278', 'BARN-DL-01', 'IN_BARN'),
('COW-0002', 'BARN-DL-01', 'IN_BARN'),
('COW-0003', 'BARN-DL-01', 'IN_BARN')
ON CONFLICT (animal_id) DO NOTHING;