-- V17: live barn counts, login farm scope, sortable lists, honest NADCS transmission log.
-- Idempotent: safe to run again.

-- 1. Farm scope at login.
--    RbacScopeService (every request) reads users.farm_id, but AuthController (login) read user_farm_scopes, and that
--    table was only ever filled for manager_daklak and manager_gialai. manager_dongnai and every account created from
--    the Users page logged in with farm "ALL" and an empty scope, so the web layer filtered all their readers out.
--    AuthController now reads users.farm_id too; this copies it so both tables agree.
INSERT INTO user_farm_scopes (scope_id, user_id, farm_id)
SELECT 'SCP-' || substr(md5(u.user_id || ':' || u.farm_id), 1, 16), u.user_id, u.farm_id
FROM users u
JOIN farms f ON f.farm_id = u.farm_id
WHERE u.farm_id IS NOT NULL
  AND u.role IN ('FARM_MANAGER', 'BARN_SUPERVISOR')
ON CONFLICT (user_id, farm_id) DO NOTHING;

-- 2. Sorting: "newest first" needs a creation time on every row and an index to sort 25,000 cattle by it.
UPDATE animals SET created_at = COALESCE(registered_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL;
ALTER TABLE animals ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE animals ALTER COLUMN created_at SET NOT NULL;
UPDATE gates SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL;
ALTER TABLE gates ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE gates ALTER COLUMN created_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_animals_created_at   ON animals (created_at DESC, animal_id);
CREATE INDEX IF NOT EXISTS idx_gates_created_at     ON gates (created_at DESC, gate_id);
CREATE INDEX IF NOT EXISTS idx_movements_occurred   ON movements (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_presence_barn_status ON presence_states (current_barn_id, presence_status);
CREATE INDEX IF NOT EXISTS idx_read_events_reader   ON rfid_read_events (reader_name, observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_actor_event    ON audit_entries (actor_id, event_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_action_event   ON audit_entries (action, event_at DESC);

-- 3. Live barn counts.
--    barns.current_headcount is a counter that only moves on reads, transfers and removals. Cattle that were registered
--    into a barn, or loaded by a script, never moved it, so a barn full of cattle showed 0. Every screen now reads
--    this view instead. The counter column stays (the ingestion code still adjusts it) but nothing displays it.
--      assigned_count     active cattle whose home barn is this barn
--      in_barn_count      cattle whose last gate pass was an IN at this barn
--      out_pasture_count  cattle whose last gate pass was an OUT at this barn (out now, not yet back)
--      unseen_count       assigned cattle that no reader has ever seen (location unknown)
CREATE OR REPLACE VIEW v_barn_counts AS
SELECT b.barn_id,
       COALESCE(a.assigned_count, 0)    AS assigned_count,
       COALESCE(p.in_barn_count, 0)     AS in_barn_count,
       COALESCE(p.out_pasture_count, 0) AS out_pasture_count,
       COALESCE(a.unseen_count, 0)      AS unseen_count
FROM barns b
LEFT JOIN (
    SELECT an.assigned_barn_id,
           COUNT(*)                                        AS assigned_count,
           COUNT(*) FILTER (WHERE ps.animal_id IS NULL)    AS unseen_count
    FROM animals an
    LEFT JOIN presence_states ps ON ps.animal_id = an.animal_id
    WHERE an.lifecycle_status = 'ACTIVE'
    GROUP BY an.assigned_barn_id
) a ON a.assigned_barn_id = b.barn_id
LEFT JOIN (
    SELECT ps.current_barn_id,
           COUNT(*) FILTER (WHERE ps.presence_status = 'IN_BARN')     AS in_barn_count,
           COUNT(*) FILTER (WHERE ps.presence_status = 'OUT_PASTURE') AS out_pasture_count
    FROM presence_states ps
    JOIN animals an ON an.animal_id = ps.animal_id AND an.lifecycle_status = 'ACTIVE'
    GROUP BY ps.current_barn_id
) p ON p.current_barn_id = b.barn_id;

-- Repair the stored counter once so anything still reading it is right at the moment of the upgrade.
UPDATE barns b SET current_headcount = c.in_barn_count
FROM v_barn_counts c
WHERE c.barn_id = b.barn_id AND b.current_headcount IS DISTINCT FROM c.in_barn_count;

-- 4. NADCS transmission log: say what really happened.
--    Until now a transmit to anything but localhost made no network call and stored an invented "ACCEPTED 202 Receipt #...".
--    delivery_status: DELIVERED (2xx from the agency), REJECTED (non-2xx), UNREACHABLE (no answer), SIMULATED (nothing sent).
ALTER TABLE external_transmissions ADD COLUMN IF NOT EXISTS delivery_status VARCHAR(20);
UPDATE external_transmissions
SET delivery_status = CASE
        WHEN endpoint_url ~ '^http://(localhost|127\.0\.0\.1)' AND http_status BETWEEN 200 AND 299 THEN 'DELIVERED'
        WHEN endpoint_url ~ '^http://(localhost|127\.0\.0\.1)' AND http_status >= 400 THEN 'REJECTED'
        WHEN endpoint_url ~ '^http://(localhost|127\.0\.0\.1)' THEN 'UNREACHABLE'
        ELSE 'SIMULATED'
    END
WHERE delivery_status IS NULL;
UPDATE external_transmissions
SET response_summary = 'SIMULATED, nothing was sent. ' || COALESCE(response_summary, '')
WHERE delivery_status = 'SIMULATED' AND COALESCE(response_summary, '') NOT LIKE 'SIMULATED%';
ALTER TABLE external_transmissions ALTER COLUMN delivery_status SET DEFAULT 'SIMULATED';
ALTER TABLE external_transmissions ALTER COLUMN delivery_status SET NOT NULL;
ALTER TABLE external_transmissions ALTER COLUMN http_status DROP NOT NULL;   -- a simulation or an unreachable agency has no HTTP status
ALTER TABLE external_transmissions DROP CONSTRAINT IF EXISTS ck_transmissions_delivery_status;
ALTER TABLE external_transmissions ADD CONSTRAINT ck_transmissions_delivery_status
    CHECK (delivery_status IN ('DELIVERED', 'REJECTED', 'UNREACHABLE', 'SIMULATED'));
