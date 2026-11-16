-- 1. Add per-barn entrance/exit cycle counters
ALTER TABLE barns ADD COLUMN IF NOT EXISTS daily_in_count INT NOT NULL DEFAULT 0;
ALTER TABLE barns ADD COLUMN IF NOT EXISTS daily_out_count INT NOT NULL DEFAULT 0;
ALTER TABLE barns ADD COLUMN IF NOT EXISTS cycle_reset_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

-- 2. Indexes for fast movement counting & return reconciliation
CREATE INDEX IF NOT EXISTS idx_movements_gate_occurred ON movements(gate_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_movements_direction_occurred ON movements(direction, occurred_at DESC);

-- 3. Initialize baseline grazing cycle counters from current presence states
UPDATE barns b
SET daily_out_count = COALESCE(sub.out_count, 0),
    daily_in_count = 0
FROM (
    SELECT current_barn_id, COUNT(*) AS out_count
    FROM presence_states
    WHERE presence_status = 'OUT_PASTURE'
    GROUP BY current_barn_id
) sub
WHERE b.barn_id = sub.current_barn_id;