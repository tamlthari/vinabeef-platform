-- Surveillance data consistency report. Read-only. Run it on the live database:
--     psql -h <host> -U vinabeef_user -d vinabeef_db -f scripts/surveillance_consistency.sql
-- Every query answers one question; a non-zero "problem" column is something to look at. Definition used everywhere:
-- "vaccinated" = the animal has at least one VACCINATION record (no validity period is applied).

\echo '== 1. Herd, by farm: dashboard definition vs surveillance definition (the two columns must be identical) =='
SELECT f.name AS farm,
       COUNT(a.animal_id)                                                                                   AS active_herd,
       COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM health_records hr WHERE hr.animal_id = a.animal_id AND hr.record_type = 'VACCINATION')) AS vaccinated_dashboard,
       COUNT(v.animal_id)                                                                                   AS vaccinated_surveillance,
       COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM health_records hr WHERE hr.animal_id = a.animal_id AND hr.record_type = 'VACCINATION'))
         - COUNT(v.animal_id)                                                                               AS problem
FROM farms f
JOIN barns b ON b.farm_id = f.farm_id
LEFT JOIN animals a ON a.assigned_barn_id = b.barn_id AND a.lifecycle_status = 'ACTIVE'
LEFT JOIN (SELECT DISTINCT animal_id FROM health_records WHERE record_type = 'VACCINATION') v ON v.animal_id = a.animal_id
GROUP BY f.name ORDER BY f.name;

\echo '== 2. Health status on the animal vs what the clinical records say =='
SELECT 'status not one of HEALTHY / UNDER_TREATMENT / QUARANTINE' AS finding, COUNT(*) AS problem
FROM animals WHERE lifecycle_status = 'ACTIVE' AND COALESCE(health_status, 'HEALTHY') NOT IN ('HEALTHY', 'UNDER_TREATMENT', 'QUARANTINE')
UNION ALL
SELECT 'UNDER_TREATMENT / QUARANTINE with no treatment, quarantine or health-check record', COUNT(*)
FROM animals a WHERE a.lifecycle_status = 'ACTIVE' AND a.health_status IN ('UNDER_TREATMENT', 'QUARANTINE')
  AND NOT EXISTS (SELECT 1 FROM health_records hr WHERE hr.animal_id = a.animal_id AND hr.record_type IN ('TREATMENT', 'QUARANTINE', 'HEALTH_CHECK'))
UNION ALL
SELECT 'HEALTHY although a treatment was recorded in the last 7 days (confirm the course has ended)', COUNT(*)
FROM animals a WHERE a.lifecycle_status = 'ACTIVE' AND COALESCE(a.health_status, 'HEALTHY') = 'HEALTHY'
  AND EXISTS (SELECT 1 FROM health_records hr WHERE hr.animal_id = a.animal_id AND hr.record_type = 'TREATMENT' AND hr.event_at >= NOW() - INTERVAL '7 days');

\echo '== 3. Vaccination records: things the national report cannot use =='
SELECT 'vaccination dated in the future' AS finding, COUNT(*) AS problem FROM health_records WHERE record_type = 'VACCINATION' AND event_at > NOW() + INTERVAL '1 hour'
UNION ALL
SELECT 'vaccination without a vaccine name (cannot be told FMD / LSD / other)', COUNT(*) FROM health_records WHERE record_type = 'VACCINATION' AND COALESCE(payload->>'vaccine', '') = ''
UNION ALL
SELECT 'vaccination without a batch number', COUNT(*) FROM health_records WHERE record_type = 'VACCINATION' AND COALESCE(payload->>'batch', '') = ''
UNION ALL
SELECT 'vaccination without the person who administered it', COUNT(*) FROM health_records WHERE record_type = 'VACCINATION' AND COALESCE(administered_by, '') = ''
UNION ALL
SELECT 'health records of animals that are no longer ACTIVE (left out of national totals)', COUNT(*)
FROM health_records hr JOIN animals a ON a.animal_id = hr.animal_id WHERE a.lifecycle_status <> 'ACTIVE';

\echo '== 4. Vaccine names on file (so you can see how FMD / LSD are matched) =='
SELECT COALESCE(NULLIF(payload->>'vaccine', ''), '(no name)') AS vaccine, COUNT(*) AS records, COUNT(DISTINCT animal_id) AS animals
FROM health_records WHERE record_type = 'VACCINATION' GROUP BY 1 ORDER BY 2 DESC;

\echo '== 5. Vaccination age: animals whose NEWEST vaccination is older than 180 / 365 days (policy decides what counts as protected) =='
SELECT COUNT(*) FILTER (WHERE newest < NOW() - INTERVAL '180 days') AS older_than_180d,
       COUNT(*) FILTER (WHERE newest < NOW() - INTERVAL '365 days') AS older_than_365d,
       COUNT(*)                                                      AS animals_with_a_vaccination
FROM (SELECT hr.animal_id, MAX(hr.event_at) AS newest FROM health_records hr JOIN animals a ON a.animal_id = hr.animal_id AND a.lifecycle_status = 'ACTIVE'
      WHERE hr.record_type = 'VACCINATION' GROUP BY hr.animal_id) x;

\echo '== 6. Animals the gates can never see: no active RFID tag =='
SELECT f.name AS farm, COUNT(*) AS problem
FROM animals a JOIN barns b ON b.barn_id = a.assigned_barn_id JOIN farms f ON f.farm_id = b.farm_id
WHERE a.lifecycle_status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM tag_assignments ta WHERE ta.animal_id = a.animal_id AND ta.valid_to IS NULL)
GROUP BY f.name ORDER BY f.name;

\echo '== 7. National transmissions: what was really delivered =='
SELECT delivery_status, COUNT(*) AS transmissions, MAX(transmitted_at) AS latest
FROM external_transmissions GROUP BY delivery_status ORDER BY 1;
\echo '   (SIMULATED = nothing was sent. Only DELIVERED means the agency answered 2xx.)'
SELECT transmission_id, report_type, farm_scope, record_count, delivery_status, http_status, LEFT(response_summary, 90) AS answer, transmitted_by, transmitted_at
FROM external_transmissions ORDER BY transmitted_at DESC LIMIT 10;
