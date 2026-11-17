-- 1. Add farm_id & display name to users table for Farm Manager isolation
ALTER TABLE users ADD COLUMN IF NOT EXISTS farm_id VARCHAR(64) REFERENCES farms(farm_id);
ALTER TABLE users ADD COLUMN IF NOT EXISTS full_name VARCHAR(120);

-- 2. Many-to-Many Barn Assignment Table (One Barn Supervisor -> One or Many Barns)
CREATE TABLE IF NOT EXISTS user_barn_assignments (
    username VARCHAR(100) NOT NULL,
    barn_id VARCHAR(64) NOT NULL REFERENCES barns(barn_id) ON DELETE CASCADE,
    assigned_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (username, barn_id)
);

CREATE INDEX IF NOT EXISTS idx_user_barn_assignments_user ON user_barn_assignments(username);
CREATE INDEX IF NOT EXISTS idx_users_farm_id ON users(farm_id);

-- 3. Configure existing accounts with explicit scopes
UPDATE users SET full_name = 'Enterprise System Admin', farm_id = NULL WHERE username = 'admin';
UPDATE users SET full_name = 'Executive Board Viewer', role = 'EXECUTIVE', farm_id = NULL WHERE username = 'executive';
UPDATE users SET full_name = 'Dak Lak Farm Manager', role = 'FARM_MANAGER', farm_id = 'FARM-DAK-LAK' WHERE username = 'manager_daklak';

-- 4. Seed Gia Lai & Dong Nai Farm Managers + Multi-Barn Supervisors (reusing bcrypt hash from admin)
INSERT INTO users (user_id, username, password_hash, role, farm_id, full_name)
SELECT 'USR-MGR-GL', 'manager_gialai', password_hash, 'FARM_MANAGER', 'FARM-GIA-LAI', 'Gia Lai Farm Manager'
FROM users WHERE username = 'admin'
ON CONFLICT (username) DO UPDATE SET role = 'FARM_MANAGER', farm_id = 'FARM-GIA-LAI', full_name = 'Gia Lai Farm Manager';

INSERT INTO users (user_id, username, password_hash, role, farm_id, full_name)
SELECT 'USR-MGR-DN', 'manager_dongnai', password_hash, 'FARM_MANAGER', 'FARM-DONG-NAI', 'Dong Nai Farm Manager'
FROM users WHERE username = 'admin'
ON CONFLICT (username) DO UPDATE SET role = 'FARM_MANAGER', farm_id = 'FARM-DONG-NAI', full_name = 'Dong Nai Farm Manager';

INSERT INTO users (user_id, username, password_hash, role, farm_id, full_name)
SELECT 'USR-SUP-DL1', 'supervisor_daklak', password_hash, 'BARN_SUPERVISOR', 'FARM-DAK-LAK', 'Dak Lak Senior Barn Supervisor (3 Barns)'
FROM users WHERE username = 'admin'
ON CONFLICT (username) DO UPDATE SET role = 'BARN_SUPERVISOR', farm_id = 'FARM-DAK-LAK', full_name = 'Dak Lak Senior Barn Supervisor (3 Barns)';

INSERT INTO users (user_id, username, password_hash, role, farm_id, full_name)
SELECT 'USR-SUP-DL2', 'supervisor_barn1', password_hash, 'BARN_SUPERVISOR', 'FARM-DAK-LAK', 'Dak Lak Barn Supervisor (2 Barns)'
FROM users WHERE username = 'admin'
ON CONFLICT (username) DO UPDATE SET role = 'BARN_SUPERVISOR', farm_id = 'FARM-DAK-LAK', full_name = 'Dak Lak Barn Supervisor (2 Barns)';

INSERT INTO users (user_id, username, password_hash, role, farm_id, full_name)
SELECT 'USR-SUP-GL1', 'supervisor_gialai', password_hash, 'BARN_SUPERVISOR', 'FARM-GIA-LAI', 'Gia Lai Barn Supervisor (2 Barns)'
FROM users WHERE username = 'admin'
ON CONFLICT (username) DO UPDATE SET role = 'BARN_SUPERVISOR', farm_id = 'FARM-GIA-LAI', full_name = 'Gia Lai Barn Supervisor (2 Barns)';

-- 5. Assign multiple barns to each Barn Supervisor (1-to-Many)
INSERT INTO user_barn_assignments (username, barn_id)
SELECT 'supervisor_daklak', barn_id
FROM barns
WHERE farm_id = 'FARM-DAK-LAK'
ORDER BY current_headcount DESC, code ASC
LIMIT 3
ON CONFLICT DO NOTHING;

INSERT INTO user_barn_assignments (username, barn_id)
SELECT 'supervisor_barn1', barn_id
FROM barns
WHERE farm_id = 'FARM-DAK-LAK'
ORDER BY code ASC
LIMIT 2
ON CONFLICT DO NOTHING;

INSERT INTO user_barn_assignments (username, barn_id)
SELECT 'supervisor_gialai', barn_id
FROM barns
WHERE farm_id = 'FARM-GIA-LAI'
ORDER BY current_headcount DESC, code ASC
LIMIT 2
ON CONFLICT DO NOTHING;