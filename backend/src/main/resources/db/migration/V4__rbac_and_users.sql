-- 1. Users Table
CREATE TABLE users (
    user_id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    role VARCHAR(30) NOT NULL, -- 'SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR', 'EXECUTIVE'
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_username ON users(username);

-- 2. Farm Scope Assignments (Enforces farm-scoped RBAC per ADR-005)
CREATE TABLE user_farm_scopes (
    scope_id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    farm_id VARCHAR(36) NOT NULL REFERENCES farms(farm_id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, farm_id)
);

CREATE INDEX idx_user_farm_scopes_user ON user_farm_scopes(user_id);

-- 3. Seed Users for All Four Roles (BCrypt hashed password: "password123")
-- Hash: $2a$10$wK1W2G6uD.R4R7x0bBvBxe6tD2e6E5i3uL0o1y2q3w4e5r6t7y8u
INSERT INTO users (user_id, username, password_hash, full_name, role, enabled) VALUES
('USR-ADMIN-01', 'admin', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjAkRB.564OnW8.Gnkz.u1GzXy0V9Tu', 'System Administrator', 'SYSTEM_ADMIN', true),
('USR-MGR-DL',   'manager_daklak', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjAkRB.564OnW8.Gnkz.u1GzXy0V9Tu', 'Dak Lak Farm Manager', 'FARM_MANAGER', true),
('USR-MGR-GL',   'manager_gialai', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjAkRB.564OnW8.Gnkz.u1GzXy0V9Tu', 'Gia Lai Farm Manager', 'FARM_MANAGER', true),
('USR-SUP-01',   'supervisor_barn1', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjAkRB.564OnW8.Gnkz.u1GzXy0V9Tu', 'Barn 01 Supervisor', 'BARN_SUPERVISOR', true),
('USR-EXEC-01',  'executive', '$2a$10$EblZqNptyYvcLm/VwDCVAuBjAkRB.564OnW8.Gnkz.u1GzXy0V9Tu', 'Executive Leadership', 'EXECUTIVE', true)
ON CONFLICT (username) DO NOTHING;

-- 4. Scope Assignments (ADR-005)
-- manager_daklak is restricted to FARM-DAK-LAK
INSERT INTO user_farm_scopes (scope_id, user_id, farm_id) VALUES
('SCP-DL-01', 'USR-MGR-DL', 'FARM-DAK-LAK'),
('SCP-GL-01', 'USR-MGR-GL', 'FARM-GIA-LAI')
ON CONFLICT (user_id, farm_id) DO NOTHING;