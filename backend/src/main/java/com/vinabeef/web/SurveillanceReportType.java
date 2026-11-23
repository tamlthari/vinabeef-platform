package com.vinabeef.web;

import java.util.Optional;

/**
 * The disease-surveillance reports: one constant per report, holding its statement and how many farm parameters
 * the statement takes. Adding a report means adding a constant here (and a menu entry in the frontend); nothing
 * else in the backend lists the types.
 *
 * <p>Every statement takes the farm filter as {@code (allFarms, farmId)} and returns at most
 * {@link SurveillanceQueries#MAX_ROWS} + 1 rows, so a cut can be reported instead of hidden. A report that needs a
 * different number of farm parameters says so in its constructor argument; {@code SurveillanceReportTypeTest} checks
 * that the number matches the statement.
 */
enum SurveillanceReportType {

    /** Herd health per farm: healthy, under treatment, quarantined, vaccinated. */
    OUTBREAK_SURVEILLANCE(2) {
        @Override
        String select() {
            String vaccinated = "SELECT hr.animal_id, BOOL_OR(" + SurveillanceQueries.FMD + ") AS is_fmd, "
                    + "BOOL_OR(" + SurveillanceQueries.LSD + ") AS is_lsd "
                    + "FROM health_records hr WHERE hr.record_type = 'VACCINATION' GROUP BY hr.animal_id";
            return """
                    SELECT f.farm_id, f.name AS farm_name, f.location,
                           COUNT(DISTINCT b.barn_id) AS barn_count,
                           COUNT(DISTINCT a.animal_id) AS active_herd_count,
                           COUNT(DISTINCT CASE WHEN a.health_status = 'HEALTHY' THEN a.animal_id END) AS healthy_count,
                           COUNT(DISTINCT CASE WHEN a.health_status = 'UNDER_TREATMENT' THEN a.animal_id END) AS under_treatment_count,
                           COUNT(DISTINCT CASE WHEN a.health_status = 'QUARANTINE' THEN a.animal_id END) AS quarantined_count,
                           COUNT(DISTINCT v.animal_id) AS vaccinated_any_count,
                           COUNT(DISTINCT v.animal_id) FILTER (WHERE v.is_fmd) AS fmd_vaccinated_count,
                           COUNT(DISTINCT v.animal_id) FILTER (WHERE v.is_lsd) AS lsd_vaccinated_count
                    FROM farms f
                    LEFT JOIN barns b ON f.farm_id = b.farm_id
                    LEFT JOIN animals a ON b.barn_id = a.assigned_barn_id AND a.lifecycle_status = 'ACTIVE'
                    LEFT JOIN (""" + vaccinated + """
                    ) v ON a.animal_id = v.animal_id
                    WHERE (? = TRUE OR f.farm_id = ?)
                    GROUP BY f.farm_id, f.name, f.location
                    ORDER BY f.farm_id""";
        }
    },

    /** Animal transfers between barns and farms; takes the farm id twice (origin or destination). */
    MOVEMENT_TRANSFERS(3) {
        @Override
        String select() {
            return """
                    SELECT t.transfer_id, t.animal_id, t.transfer_type,
                           COALESCE(ff.name, t.from_farm_id) AS origin_farm,
                           COALESCE(fb.code, t.from_barn_id) AS origin_barn,
                           COALESCE(tf.name, t.to_farm_id) AS destination_farm,
                           COALESCE(tb.code, t.to_barn_id) AS destination_barn,
                           t.reason, t.approved_by, t.transferred_at::text AS timestamp
                    FROM animal_transfers t
                    LEFT JOIN barns fb ON t.from_barn_id = fb.barn_id
                    LEFT JOIN farms ff ON t.from_farm_id = ff.farm_id
                    LEFT JOIN barns tb ON t.to_barn_id = tb.barn_id
                    LEFT JOIN farms tf ON t.to_farm_id = tf.farm_id
                    WHERE (? = TRUE OR t.from_farm_id = ? OR t.to_farm_id = ?)
                    ORDER BY t.transferred_at DESC, t.transfer_id""";
        }
    },

    /** Treatments, vaccinations and quarantine records. */
    HEALTH_TREATMENTS(2) {
        @Override
        String select() {
            return """
                    SELECT hr.health_id, hr.animal_id, a.health_status AS current_clinical_status,
                           f.name AS farm_name, b.code AS barn_code,
                           hr.record_type, hr.administered_by, hr.payload::text AS clinical_payload,
                           hr.event_at::text AS recorded_at
                    FROM health_records hr
                    JOIN animals a ON hr.animal_id = a.animal_id AND a.lifecycle_status = 'ACTIVE'
                    LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
                    LEFT JOIN farms f ON b.farm_id = f.farm_id
                    WHERE (? = TRUE OR f.farm_id = ?)
                    ORDER BY hr.event_at DESC, hr.health_id""";
        }
    },

    /** Vaccinated share of the herd, barn by barn. */
    VACCINATION_COVERAGE(2) {
        @Override
        String select() {
            return """
                    SELECT f.farm_id, f.name AS farm_name, b.barn_id, b.code AS barn_code,
                           COUNT(a.animal_id) AS total_cattle,
                           COUNT(v.animal_id) AS vaccinated_cattle,
                           ROUND(CASE WHEN COUNT(a.animal_id) > 0 THEN (COUNT(v.animal_id) * 100.0) / COUNT(a.animal_id) ELSE 0 END, 2) AS coverage_pct
                    FROM farms f
                    JOIN barns b ON f.farm_id = b.farm_id
                    LEFT JOIN animals a ON b.barn_id = a.assigned_barn_id AND a.lifecycle_status = 'ACTIVE'
                    LEFT JOIN (SELECT DISTINCT animal_id FROM health_records WHERE record_type = 'VACCINATION') v ON a.animal_id = v.animal_id
                    WHERE (? = TRUE OR f.farm_id = ?)
                    GROUP BY f.farm_id, f.name, b.barn_id, b.code
                    ORDER BY f.farm_id, b.code""";
        }
    };

    /** Used when the caller names no known type: the summary, as before. */
    static final SurveillanceReportType DEFAULT = OUTBREAK_SURVEILLANCE;

    private final int farmParams;

    SurveillanceReportType(int farmParams) {
        this.farmParams = farmParams;
    }

    /** The statement without its row limit. */
    abstract String select();

    /** The statement with the row limit, ready to run. */
    String sql() {
        return select() + " LIMIT " + (SurveillanceQueries.MAX_ROWS + 1);
    }

    /** How many parameters the statement takes: {@code (allFarms, farmId)}, or one more farm id for transfers. */
    int farmParams() {
        return farmParams;
    }

    /** The exact name only ({@code "MOVEMENT_TRANSFERS"}); anything else, including null, is not a report. */
    static Optional<SurveillanceReportType> tryParse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (SurveillanceReportType type : values()) {
            if (type.name().equals(name)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
