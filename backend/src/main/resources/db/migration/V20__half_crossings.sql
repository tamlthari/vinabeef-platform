-- V20: a read at one reader of an inner/outer pair is recorded at once, as a HALF_CROSSING movement, and is completed
-- by the other reader of the pair whenever that read comes (no time limit). Idempotent: safe to run again.
--
--   crossing_status    COMPLETE (default: a crossing seen by both readers, or by the one reader of a single-reader or
--                      one-way gate) or HALF_CROSSING (seen by one reader of a pair, the other has not seen the animal yet)
--   reader_side        INNER or OUTER: the reader that saw a HALF_CROSSING. NULL once the crossing is complete, and NULL for
--                      a half crossing whose reader pair was changed afterwards (it can no longer be completed)
--   previous_presence  the animal's presence (IN_BARN / OUT_PASTURE) before this movement
--   previous_barn_id   the barn the animal was counted in before this movement
--                      Both are kept so that a half crossing can be corrected when the other reader's read shows the
--                      animal went the other way (the order of the two reads is the direction).
--
-- A HALF_CROSSING carries a provisional direction (the animal's last presence turned around, the same rule as a
-- single-reader gate). Completing it confirms or corrects that direction.
ALTER TABLE movements ADD COLUMN IF NOT EXISTS crossing_status   VARCHAR(16) NOT NULL DEFAULT 'COMPLETE';
ALTER TABLE movements ADD COLUMN IF NOT EXISTS reader_side       VARCHAR(5);
ALTER TABLE movements ADD COLUMN IF NOT EXISTS previous_presence VARCHAR(20);
ALTER TABLE movements ADD COLUMN IF NOT EXISTS previous_barn_id  VARCHAR(36);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_movements_crossing_status') THEN
        ALTER TABLE movements ADD CONSTRAINT chk_movements_crossing_status
            CHECK (crossing_status IN ('COMPLETE', 'HALF_CROSSING'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_movements_reader_side') THEN
        ALTER TABLE movements ADD CONSTRAINT chk_movements_reader_side
            CHECK (reader_side IS NULL OR reader_side IN ('INNER', 'OUTER'));
    END IF;
END $$;

-- The movements an animal has at one gate, around a point in time: what the processing service reads for every read at a
-- paired gate (the latest movement at or before it, the earliest after it).
CREATE INDEX IF NOT EXISTS idx_movements_animal_gate_time ON movements (animal_id, gate_id, occurred_at DESC);

-- The pairing window is gone: a half crossing waits as long as it takes. The column stays (old software may still be
-- running against this database during an upgrade) but nothing reads it any more.
COMMENT ON COLUMN gates.pair_window_ms IS 'No longer used (since V20): a half crossing has no time limit. Safe to drop once nothing reads it.';

-- Only a two-way gate can have an inner/outer pair. A one-way gate (IN_ONLY / OUT_ONLY) has the direction fixed by the
-- gate itself, so a second reader gives it nothing. The portal already refuses such a pair; this refuses it for any other
-- way of writing to the table. It fires when a reader row is added or moved to another gate or side, NOT on the status
-- updates a reader row gets with every read, so a pair that was set up before this rule keeps working: the processing
-- service treats a one-way gate as one-way whatever its reader rows say.
CREATE OR REPLACE FUNCTION trg_gate_readers_not_on_one_way_gate() RETURNS trigger AS $$
DECLARE
    mode VARCHAR;
BEGIN
    SELECT direction_mode INTO mode FROM gates WHERE gate_id = NEW.gate_id;
    IF mode IN ('IN_ONLY', 'OUT_ONLY') THEN
        RAISE EXCEPTION 'Gate % is % (one-way): an inner/outer reader pair is only possible on a two-way (BIDIRECTIONAL) gate.',
            NEW.gate_id, mode
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS gate_readers_not_on_one_way_gate ON gate_readers;
CREATE TRIGGER gate_readers_not_on_one_way_gate
    BEFORE INSERT OR UPDATE OF gate_id, side ON gate_readers
    FOR EACH ROW EXECUTE FUNCTION trg_gate_readers_not_on_one_way_gate();

-- Tell whoever applies this about one-way gates that already have a pair (they keep working as one-way gates).
DO $$
DECLARE
    n INT;
BEGIN
    SELECT COUNT(DISTINCT g.gate_id) INTO n
      FROM gates g JOIN gate_readers gr ON gr.gate_id = g.gate_id
     WHERE g.direction_mode IN ('IN_ONLY', 'OUT_ONLY') AND g.retired_at IS NULL;
    IF n > 0 THEN
        RAISE NOTICE 'V20: % one-way gate(s) still have inner/outer reader rows from before; they are treated as one-way gates and every read at them is a complete crossing. Remove the second reader on the reader page to tidy up.', n;
    END IF;
END $$;
