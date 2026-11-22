-- V18: keep the request body of every NADCS transmission, so a row of the transmission log can show what was sent.
-- Idempotent: safe to run again.
--
-- Until now payload_snapshot held only the first 2,500 characters. From now on it holds the body as sent (cut at
-- nadcs.body-store-max-chars, default 2,000,000 characters), and these columns describe the COMPLETE body:
--   payload_bytes      exact size in bytes (UTF-8), what the HTTP request carried
--   payload_sha256     SHA-256 of those bytes; the test server records the hash of what it received, so the two can be compared
--   payload_truncated  true when payload_snapshot had to be cut
-- Rows written before this migration keep payload_bytes NULL: the screen says that only an excerpt exists for them.
ALTER TABLE external_transmissions ADD COLUMN IF NOT EXISTS payload_bytes     BIGINT;
ALTER TABLE external_transmissions ADD COLUMN IF NOT EXISTS payload_sha256    VARCHAR(64);
ALTER TABLE external_transmissions ADD COLUMN IF NOT EXISTS payload_truncated BOOLEAN NOT NULL DEFAULT FALSE;
