BEGIN;

-- 1. Ensure the 3 primary farms exist
INSERT INTO farms (farm_id, name, location) VALUES
('FARM-DAK-LAK',  'Dak Lak Cattle Station',    'Buon Ma Thuot, Dak Lak'),
('FARM-GIA-LAI',  'Gia Lai Grazing Facility',  'Pleiku, Gia Lai'),
('FARM-DONG-NAI', 'Dong Nai Feedlot',          'Long Khanh, Dong Nai')
ON CONFLICT (farm_id) DO NOTHING;

-- 2. Create 120 Barns (40 barns per farm: BARN-DL-001..040, BARN-GL-001..040, BARN-DN-001..040)
INSERT INTO barns (barn_id, farm_id, code, capacity, current_headcount)
SELECT
    'BARN-' || (ARRAY['DL', 'GL', 'DN'])[((g - 1) / 40) + 1] || '-' || lpad((((g - 1) % 40) + 1)::text, 3, '0') AS barn_id,
    (ARRAY['FARM-DAK-LAK', 'FARM-GIA-LAI', 'FARM-DONG-NAI'])[((g - 1) / 40) + 1] AS farm_id,
    (ARRAY['DL', 'GL', 'DN'])[((g - 1) / 40) + 1] || '-BARN-' || lpad((((g - 1) % 40) + 1)::text, 3, '0') AS code,
    250 + ((g % 3) * 25) AS capacity, -- Capacity between 250 and 300 per barn
    0 AS current_headcount
FROM generate_series(1, 120) AS g
ON CONFLICT (barn_id) DO NOTHING;

-- 3. Create 1 Gate Reader Station per Barn (120 readers total, without 'Dr' prefix)
INSERT INTO gates (gate_id, barn_id, label, reader_name, direction_mode, firmware_version, status)
SELECT
    'GATE-' || (ARRAY['DL', 'GL', 'DN'])[((g - 1) / 40) + 1] || '-' || lpad((((g - 1) % 40) + 1)::text, 3, '0') AS gate_id,
    'BARN-' || (ARRAY['DL', 'GL', 'DN'])[((g - 1) / 40) + 1] || '-' || lpad((((g - 1) % 40) + 1)::text, 3, '0') AS barn_id,
    'Main Gate ' || lpad((((g - 1) % 40) + 1)::text, 3, '0') AS label,
    upper(lpad(to_hex(1000 + g), 4, '0')) AS reader_name,
    'BIDIRECTIONAL' AS direction_mode,
    '1.58.1998' AS firmware_version,
    'ONLINE' AS status
FROM generate_series(1, 120) AS g
ON CONFLICT (gate_id) DO UPDATE
SET reader_name = EXCLUDED.reader_name;

-- 4. Insert 25,000 Cattle evenly distributed across all 120 barns (~208-209 cattle/barn)
INSERT INTO animals (animal_id, assigned_barn_id, lifecycle_status, breed, weight_kg)
SELECT
    'VB-' || lpad(c::text, 6, '0') AS animal_id,
    'BARN-' || (ARRAY['DL', 'GL', 'DN'])[((((c - 1) % 120)) / 40) + 1] || '-' || lpad(((((c - 1) % 120) % 40) + 1)::text, 3, '0') AS assigned_barn_id,
    'ACTIVE' AS lifecycle_status,
    (ARRAY['Brahman Cross', 'Wagyu Cross', 'Angus', 'Charolais', 'Red Sindhi'])[1 + (c % 5)] AS breed,
    ROUND((380 + (c % 170) + ((c % 10) * 0.1))::numeric, 2) AS weight_kg
FROM generate_series(1, 25000) AS c
ON CONFLICT (animal_id) DO NOTHING;

-- 5. Insert 25,000 Active RFID Ear Tags (14-char hex UID format: 044E6602AD1490 .. 044E6602AD7637)
INSERT INTO rfid_tags (tag_id, epc_value, credential_identifier, status)
SELECT
    '044E6602' || upper(lpad(to_hex(11343247 + c), 6, '0')) AS tag_id,
    'E280117000044E6602' || upper(lpad(to_hex(11343247 + c), 6, '0')) AS epc_value,
    '044E6602' || upper(lpad(to_hex(11343247 + c), 6, '0')) AS credential_identifier,
    'ACTIVE' AS status
FROM generate_series(1, 25000) AS c
ON CONFLICT (tag_id) DO NOTHING;

-- 6. Bind all 25,000 RFID Tags to the 25,000 Cattle
INSERT INTO tag_assignments (assignment_id, animal_id, tag_id, valid_from)
SELECT
    'ASG-' || lpad(c::text, 6, '0') AS assignment_id,
    'VB-' || lpad(c::text, 6, '0') AS animal_id,
    '044E6602' || upper(lpad(to_hex(11343247 + c), 6, '0')) AS tag_id,
    CURRENT_TIMESTAMP - ((c % 90) || ' days')::interval AS valid_from
FROM generate_series(1, 25000) AS c
ON CONFLICT (assignment_id) DO UPDATE
SET tag_id = EXCLUDED.tag_id;

-- 7. Seed Presence States (75% IN_BARN, 25% OUT_PASTURE)
INSERT INTO presence_states (animal_id, current_barn_id, presence_status, updated_at)
SELECT
    'VB-' || lpad(c::text, 6, '0') AS animal_id,
    'BARN-' || (ARRAY['DL', 'GL', 'DN'])[((((c - 1) % 120)) / 40) + 1] || '-' || lpad(((((c - 1) % 120) % 40) + 1)::text, 3, '0') AS current_barn_id,
    CASE WHEN (c % 4) = 0 THEN 'OUT_PASTURE' ELSE 'IN_BARN' END AS presence_status,
    CURRENT_TIMESTAMP
FROM generate_series(1, 25000) AS c
ON CONFLICT (animal_id) DO NOTHING;

-- 8. Synchronize each barn's current_headcount with actual IN_BARN cattle
UPDATE barns b
SET current_headcount = sub.in_barn_count
FROM (
    SELECT current_barn_id, COUNT(*) AS in_barn_count
    FROM presence_states
    WHERE presence_status = 'IN_BARN'
    GROUP BY current_barn_id
) sub
WHERE b.barn_id = sub.current_barn_id;

-- 9. Seed Vaccination Records for 80% of the herd (20,000 cattle)
INSERT INTO health_records (health_id, animal_id, record_type, administered_by, payload, event_at)
SELECT
    'HLT-' || lpad(c::text, 6, '0') AS health_id,
    'VB-' || lpad(c::text, 6, '0') AS animal_id,
    'VACCINATION' AS record_type,
    (ARRAY['Dr. Nguyen Van A', 'Dr. Le Thi B', 'Dr. Tran Minh C'])[1 + (c % 3)] AS administered_by,
    jsonb_build_object(
        'vaccine', (ARRAY['Foot-and-Mouth Disease (FMD)', 'Lumpy Skin Disease (LSD)', 'Bovine Rhinotracheitis (IBR)'])[1 + (c % 3)],
        'batch', 'BATCH-2026-Q4'
    ) AS payload,
    CURRENT_TIMESTAMP - ((c % 60) || ' days')::interval AS event_at
FROM generate_series(1, 25000) AS c
WHERE (c % 5) <> 0
ON CONFLICT (health_id) DO NOTHING;

COMMIT;