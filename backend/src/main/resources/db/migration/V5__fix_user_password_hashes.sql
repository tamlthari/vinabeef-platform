-- Fix BCrypt hash for 'password123' across all seed accounts
UPDATE users 
SET password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeTfmMdmN8eFKxR3xiDBAJqt.vWQw1.JW'
WHERE username IN ('admin', 'manager_daklak', 'manager_gialai', 'supervisor_barn1', 'executive');