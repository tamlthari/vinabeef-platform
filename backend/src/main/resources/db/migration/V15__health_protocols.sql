-- V15: vaccination / treatment protocols for the field app
--
-- The mobile sync bundle used to return a fixed list written in Java. Protocols now live in a table so a farm
-- manager can add or retire them without a release.
--
-- Dose, route and booster interval are NOT pre-filled. They depend on the vaccine product and the local
-- veterinary policy, so they are left empty here and are meant to be entered from the product label.
-- Batch/lot numbers are never pre-filled either: the person vaccinating enters the real lot of the vial.

CREATE TABLE IF NOT EXISTS health_protocols (
    protocol_id      VARCHAR(40)  PRIMARY KEY,
    record_type      VARCHAR(20)  NOT NULL CHECK (record_type IN ('VACCINATION', 'TREATMENT', 'HEALTH_CHECK', 'QUARANTINE')),
    title            VARCHAR(120) NOT NULL,
    resulting_status VARCHAR(20)  NOT NULL DEFAULT 'HEALTHY' CHECK (resulting_status IN ('HEALTHY', 'UNDER_TREATMENT', 'QUARANTINE')),
    default_dose_ml  NUMERIC(5,2),                                 -- optional, from the product label
    route            VARCHAR(10)  CHECK (route IN ('SC', 'IM', 'ORAL', 'TOPICAL', 'OTHER')),
    interval_days    INT          CHECK (interval_days IS NULL OR interval_days > 0),   -- days until the next dose is due
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order       INT          NOT NULL DEFAULT 100
);

INSERT INTO health_protocols (protocol_id, record_type, title, resulting_status, sort_order) VALUES
    ('VAC-FMD',      'VACCINATION', 'Foot-and-Mouth Disease (FMD)',                 'HEALTHY',         10),
    ('VAC-LSD',      'VACCINATION', 'Lumpy Skin Disease (LSD)',                     'HEALTHY',         20),
    ('VAC-IBR',      'VACCINATION', 'Bovine Rhinotracheitis (IBR)',                 'HEALTHY',         30),
    ('TRT-OXY',      'TREATMENT',   'Antibiotic Course (Oxytetracycline 10ml)',     'UNDER_TREATMENT', 40),
    ('CHK-ROUTINE',  'HEALTH_CHECK','Routine Pen Clinical Check',                   'HEALTHY',         50),
    ('QRN-FEVER',    'QUARANTINE',  'Suspected Fever / Isolation Required',         'QUARANTINE',      60)
ON CONFLICT (protocol_id) DO NOTHING;

-- Look-ups of "when was this animal last given this vaccine"
CREATE INDEX IF NOT EXISTS idx_health_records_vaccination
    ON health_records (animal_id, record_type, event_at DESC);
