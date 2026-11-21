-- V16: one-way (IN only / OUT only) gates, retiring readers, archiving cattle
--
-- 1. gates.direction_mode becomes the authoritative direction rule of a gate:
--      BIDIRECTIONAL  both directions pass the gate. Two readers (inner/outer) decide by the order of their
--                     reads (delta time). A single reader decides by the animal's last known presence.
--      IN_ONLY        a return gate: any read is an animal coming IN (back from pasture).
--      OUT_ONLY       an exit gate: any read is an animal going OUT to pasture.
--    The column was free text and the application silently treated unknown values as BIDIRECTIONAL.
-- 2. Readers and cattle that already have history cannot be deleted (movements.gate_id and
--    movements.animal_id are ON DELETE RESTRICT on purpose, they are the traceability record).
--    They are retired / archived instead, and hidden from the working lists.

-- 1. Direction rule -----------------------------------------------------------------------------------------
UPDATE gates SET direction_mode = UPPER(BTRIM(direction_mode)) WHERE direction_mode IS NOT NULL;

-- A gate that already has an inner/outer pair has been measuring direction by the pair. Keep it that way.
UPDATE gates g SET direction_mode = 'BIDIRECTIONAL'
WHERE EXISTS (SELECT 1 FROM gate_readers r WHERE r.gate_id = g.gate_id);

-- Unknown or missing values were already handled as BIDIRECTIONAL by the application.
UPDATE gates SET direction_mode = 'BIDIRECTIONAL'
WHERE direction_mode IS NULL OR direction_mode NOT IN ('BIDIRECTIONAL', 'IN_ONLY', 'OUT_ONLY');

ALTER TABLE gates ALTER COLUMN direction_mode SET DEFAULT 'BIDIRECTIONAL';
ALTER TABLE gates ALTER COLUMN direction_mode SET NOT NULL;
ALTER TABLE gates DROP CONSTRAINT IF EXISTS ck_gates_direction_mode;
ALTER TABLE gates ADD CONSTRAINT ck_gates_direction_mode
    CHECK (direction_mode IN ('BIDIRECTIONAL', 'IN_ONLY', 'OUT_ONLY'));

-- Movements record how the direction was decided:
--   PAIRED_READERS  delta time between the inner and the outer reader   (BIDIRECTIONAL, two readers)
--   GATE_MODE       presence toggle of a single reader                  (BIDIRECTIONAL, one reader)
--   ONE_WAY_GATE    fixed by the gate: IN_ONLY = IN, OUT_ONLY = OUT     (new)
-- Older movements of IN_ONLY / OUT_ONLY gates keep the label GATE_MODE; the direction column is the same.

-- 2. Retired readers (gates) --------------------------------------------------------------------------------
ALTER TABLE gates ADD COLUMN IF NOT EXISTS retired_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE gates ADD COLUMN IF NOT EXISTS retired_by VARCHAR(100);

-- A retired gate keeps its reader_name for the history, but the name may be registered again.
DROP INDEX IF EXISTS uq_gates_reader_name;
CREATE UNIQUE INDEX uq_gates_reader_name
    ON gates (reader_name) WHERE reader_name IS NOT NULL AND retired_at IS NULL;

-- 3. Archived cattle ----------------------------------------------------------------------------------------
-- lifecycle_status = 'ARCHIVED': removed from the herd, history kept.
ALTER TABLE animals ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE animals ADD COLUMN IF NOT EXISTS archived_by VARCHAR(100);
ALTER TABLE animals ADD COLUMN IF NOT EXISTS archive_reason VARCHAR(200);
