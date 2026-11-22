-- DEMO DATA ONLY. Repairs a database that was loaded with the OLD seed_25k_cattle.sql, where every 4th barn
-- (DL-BARN-004, -008, ... ) had all of its cattle OUT at pasture and therefore showed "0 in barn".
--
-- It touches only animals that have NO recorded gate passage, so anything a real reader has reported is left alone.
-- Run once:   psql -h <host> -U vinabeef_user -d vinabeef_db -f scripts/rebalance_demo_presence.sql
BEGIN;

WITH ranked AS (
    SELECT ps.animal_id,
           ROW_NUMBER() OVER (PARTITION BY a.assigned_barn_id ORDER BY ps.animal_id) AS pos
    FROM presence_states ps
    JOIN animals a ON a.animal_id = ps.animal_id AND a.lifecycle_status = 'ACTIVE'
    WHERE NOT EXISTS (SELECT 1 FROM movements m WHERE m.animal_id = ps.animal_id)
)
UPDATE presence_states ps
SET presence_status = CASE WHEN r.pos % 4 = 0 THEN 'OUT_PASTURE' ELSE 'IN_BARN' END,
    updated_at = NOW()
FROM ranked r
WHERE ps.animal_id = r.animal_id;

-- keep the stored counter in step (the screens read the live view v_barn_counts, this column is only kept for old code)
UPDATE barns b
SET current_headcount = c.in_barn_count
FROM v_barn_counts c
WHERE c.barn_id = b.barn_id AND b.current_headcount IS DISTINCT FROM c.in_barn_count;

COMMIT;

SELECT b.code, c.assigned_count AS herd, c.in_barn_count AS in_barn, c.out_pasture_count AS at_pasture, c.unseen_count AS not_seen
FROM v_barn_counts c JOIN barns b ON b.barn_id = c.barn_id
WHERE b.code IN ('DL-BARN-004', 'DL-BARN-008', 'DN-BARN-008') ORDER BY b.code;
