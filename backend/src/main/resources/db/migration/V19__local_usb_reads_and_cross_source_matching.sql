-- V19: reads from a USB reader on the barn gateway (no Internet needed) next to the Safetrust reads, and the
-- bookkeeping that stops one physical tap that arrives by BOTH routes from being counted twice.
-- Idempotent: safe to run again.
--
--   read_source        SAFETRUST (the reader's own event, through the Safetrust cloud) or LOCAL_USB (a credential number
--                      typed by a USB reader attached to the barn gateway; the gateway adds the time, EVENT_KEY is negative)
--   source_device      the USB device that produced a LOCAL_USB read
--   time_source        READER (time from the reader) or GATEWAY_CLOCK (time added by the gateway)
--   card_resolved_by   how a LOCAL_USB credential number became a card UID: SAFETRUST_HISTORY (the number was seen with
--                      that card in real Safetrust events) or TAG_REGISTER (rfid_tags.credential_identifier)
--   duplicate_of       this read is the second copy of the read with that event_id (it never makes a movement)
--   twin_event_id      the read that is the second copy of this one
-- A LOCAL_USB read whose credential number maps to no card is kept with card_uuid NULL and event_class LOCAL_UNMAPPED.
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS read_source      VARCHAR(16) NOT NULL DEFAULT 'SAFETRUST';
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS source_device    VARCHAR(100);
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS time_source      VARCHAR(16);
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS card_resolved_by VARCHAR(24);
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS duplicate_of     VARCHAR(64);
ALTER TABLE rfid_read_events ADD COLUMN IF NOT EXISTS twin_event_id    VARCHAR(64);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_read_events_source') THEN
        ALTER TABLE rfid_read_events ADD CONSTRAINT chk_read_events_source CHECK (read_source IN ('SAFETRUST', 'LOCAL_USB'));
    END IF;
END $$;

-- looking up which card a credential number belongs to (only local reads ask)
CREATE INDEX IF NOT EXISTS idx_read_events_credential
    ON rfid_read_events (credential_identifier, hardware_timestamp DESC) WHERE credential_identifier IS NOT NULL;

-- finding the other route's copy of a read: only reads that still have no copy are candidates
CREATE INDEX IF NOT EXISTS idx_read_events_open_primary
    ON rfid_read_events (reader_name, card_uuid, hardware_timestamp)
    WHERE event_class = 'CARD_READ' AND duplicate_of IS NULL AND twin_event_id IS NULL;

-- unmapped local reads waiting for a card (normally an empty, tiny index)
CREATE INDEX IF NOT EXISTS idx_read_events_unmapped
    ON rfid_read_events (hardware_timestamp) WHERE event_class = 'LOCAL_UNMAPPED';
