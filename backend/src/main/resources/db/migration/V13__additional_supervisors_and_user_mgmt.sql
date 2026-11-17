-- 1. Seed / Update the 3 Barn Supervisor demo accounts (reusing BCrypt password_hash from admin)
INSERT INTO users (user_id, username, password_hash, role, farm_id, full_name)
SELECT 'USR-SUP-DN1', 'supervisor_dongnai', password_hash, 'BARN_SUPERVISOR', 'FARM-DONG-NAI', 'Dong Nai Barn Supervisor (3 Barns)'
FROM users WHERE username = 'admin'
ON CONFLICT (username) DO UPDATE
SET role = 'BARN_SUPERVISOR', farm_id = 'FARM-DONG-NAI', full_name = 'Dong Nai Barn Supervisor (3 Barns)';

INSERT INTO users (user_id, username, password_hash, role, farm_id, full_name)
SELECT 'USR-SUP-GL1', 'supervisor_gialai', password_hash, 'BARN_SUPERVISOR', 'FARM-GIA-LAI', 'Gia Lai Senior Barn Supervisor (3 Barns)'
FROM users WHERE username = 'admin'
ON CONFLICT (username) DO UPDATE
SET role = 'BARN_SUPERVISOR', farm_id = 'FARM-GIA-LAI', full_name = 'Gia Lai Senior Barn Supervisor (3 Barns)';

INSERT INTO users (user_id, username, password_hash, role, farm_id, full_name)
SELECT 'USR-SUP-GL2', 'supervisor_gialai_2barns', password_hash, 'BARN_SUPERVISOR', 'FARM-GIA-LAI', 'Gia Lai Barn Supervisor (2 Barns)'
FROM users WHERE username = 'admin'
ON CONFLICT (username) DO UPDATE
SET role = 'BARN_SUPERVISOR', farm_id = 'FARM-GIA-LAI', full_name = 'Gia Lai Barn Supervisor (2 Barns)';

-- 2. Reset & assign exact barn sets (1-to-Many) for each of the 3 supervisors
DELETE FROM user_barn_assignments
WHERE username IN ('supervisor_dongnai', 'supervisor_gialai', 'supervisor_gialai_2barns');

-- Dong Nai Supervisor -> 3 barns in FARM-DONG-NAI
INSERT INTO user_barn_assignments (username, barn_id)
SELECT 'supervisor_dongnai', barn_id
FROM barns
WHERE farm_id = 'FARM-DONG-NAI'
ORDER BY current_headcount DESC, code ASC
LIMIT 3
ON CONFLICT DO NOTHING;

-- Gia Lai Senior Supervisor -> 3 barns in FARM-GIA-LAI
INSERT INTO user_barn_assignments (username, barn_id)
SELECT 'supervisor_gialai', barn_id
FROM barns
WHERE farm_id = 'FARM-GIA-LAI'
ORDER BY current_headcount DESC, code ASC
LIMIT 3
ON CONFLICT DO NOTHING;

-- Gia Lai 2-Barn Supervisor -> 2 barns in FARM-GIA-LAI
INSERT INTO user_barn_assignments (username, barn_id)
SELECT 'supervisor_gialai_2barns', barn_id
FROM barns
WHERE farm_id = 'FARM-GIA-LAI'
ORDER BY code ASC
LIMIT 2
ON CONFLICT DO NOTHING;