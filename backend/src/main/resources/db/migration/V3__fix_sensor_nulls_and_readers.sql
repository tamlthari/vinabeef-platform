-- 1. Allow card_uuid to be nullable for non-badge events (door sensors, rex buttons)
ALTER TABLE rfid_read_events ALTER COLUMN card_uuid DROP NOT NULL;

-- 2. Register DrC0F8 and Dr49F9 to Barns so movements are tracked
INSERT INTO gates (gate_id, barn_id, label, reader_name, direction_mode) VALUES
('GATE-DL-01C', 'BARN-DL-01', 'East Sorting Gate', 'DrC0F8', 'BIDIRECTIONAL'),
('GATE-DL-01D', 'BARN-DL-01', 'West Run Gate', 'Dr49F9', 'BIDIRECTIONAL')
ON CONFLICT (gate_id) DO NOTHING;