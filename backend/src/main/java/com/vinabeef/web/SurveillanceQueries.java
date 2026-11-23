package com.vinabeef.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL of the disease-surveillance reports and of the data-consistency checks shown beside them.
 * Every statement takes the farm filter as two parameters {@code (allFarms, farmId)} unless noted.
 *
 * <p>Definition used everywhere (dashboard, surveillance, reports): an animal counts as <b>vaccinated</b> when it has at
 * least one VACCINATION record. The statements below count that and nothing else; the FMD / LSD columns narrow it to
 * records whose vaccine name says so. No validity period is applied: that policy has to come from the animal-health
 * authority and is deliberately not guessed here.
 */
final class SurveillanceQueries {

    /** Most rows one report carries. One more is fetched so a cut can be reported instead of hidden. */
    static final int MAX_ROWS = 50_000;

    static final String FMD = "(hr.payload->>'vaccine' ILIKE '%FMD%' OR hr.payload->>'vaccine' ILIKE '%Foot%Mouth%')";
    static final String LSD = "(hr.payload->>'vaccine' ILIKE '%LSD%' OR hr.payload->>'vaccine' ILIKE '%Lumpy%')";

    private SurveillanceQueries() {
    }

    /**
     * One transmission for its detail view. Eight parameters, in order: (allFarms, farmId) for body_visible, (allFarms, farmId)
     * for the excerpt, excerptChars, transmissionId, then (allFarms, farmId) for which rows may be seen at all.
     * The row is visible like the list (own farm or an all-farm report), but the BODY only when the caller may see every
     * farm in it: an all-farm report carries other farms' animals, so a farm-scoped account gets the metadata and
     * {@code body_excerpt = NULL}. Deciding this in SQL keeps a restricted body out of the application entirely.
     */
    static final String TRANSMISSION_DETAIL = """
        SELECT transmission_id, target_system, endpoint_url, report_type, farm_scope, record_count, payload_format,
               http_status, delivery_status, response_summary, transmitted_by, transmitted_at,
               payload_bytes, payload_sha256, payload_truncated,
               (? = TRUE OR farm_scope = ?) AS body_visible,
               COALESCE(length(payload_snapshot), 0) AS stored_chars,
               CASE WHEN (? = TRUE OR farm_scope = ?) THEN left(payload_snapshot, ?) END AS body_excerpt
        FROM external_transmissions
        WHERE transmission_id = ? AND (? = TRUE OR farm_scope = ? OR farm_scope = 'ALL')
        """;

    /** The whole stored body, same visibility rule. Parameters: (transmissionId, allFarms, farmId). */
    static final String TRANSMISSION_BODY = """
        SELECT transmission_id, payload_format, payload_snapshot, payload_sha256, payload_bytes, payload_truncated
        FROM external_transmissions
        WHERE transmission_id = ? AND (? = TRUE OR farm_scope = ?)
        """;

    static boolean isKnownType(String reportType) {
        return SurveillanceReportType.tryParse(reportType).isPresent();
    }

    /** Parameter count of {@link #report(String)}: 2 for every type except transfers (3). */
    static int farmParams(String reportType) {
        return SurveillanceReportType.tryParse(reportType).orElse(SurveillanceReportType.DEFAULT).farmParams();
    }

    /** The statement of a report. An unknown name gives the summary, as it always has; callers check {@link #isKnownType} first. */
    static String report(String reportType) {
        return SurveillanceReportType.tryParse(reportType).orElse(SurveillanceReportType.DEFAULT).sql();
    }

    /** One consistency check: a short code, what it means, how serious it is, and the counting statement. */
    record Check(String code, String label, String severity, String sql) {
    }

    private static final String ACTIVE_IN_FARM = """
        FROM animals a
        LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
        WHERE a.lifecycle_status = 'ACTIVE' AND (? = TRUE OR b.farm_id = ?)""";

    /** The checks, in display order. Each statement takes (allFarms, farmId) and returns one count. */
    static Map<String, Check> checks() {
        Map<String, Check> m = new LinkedHashMap<>();
        m.put("UNKNOWN_HEALTH_STATUS", new Check("UNKNOWN_HEALTH_STATUS",
                "Animals whose health status is not HEALTHY, UNDER_TREATMENT or QUARANTINE (not counted in any surveillance bucket)", "error",
                "SELECT COUNT(*) " + ACTIVE_IN_FARM + " AND COALESCE(a.health_status, 'HEALTHY') NOT IN ('HEALTHY', 'UNDER_TREATMENT', 'QUARANTINE')"));
        m.put("STATUS_WITHOUT_RECORD", new Check("STATUS_WITHOUT_RECORD",
                "Under treatment / quarantine with no treatment, quarantine or health-check record to back it", "warning",
                "SELECT COUNT(*) " + ACTIVE_IN_FARM + " AND a.health_status IN ('UNDER_TREATMENT', 'QUARANTINE') "
                        + "AND NOT EXISTS (SELECT 1 FROM health_records hr WHERE hr.animal_id = a.animal_id AND hr.record_type IN ('TREATMENT', 'QUARANTINE', 'HEALTH_CHECK'))"));
        m.put("RECENT_TREATMENT_BUT_HEALTHY", new Check("RECENT_TREATMENT_BUT_HEALTHY",
                "Marked HEALTHY although a treatment was recorded in the last 7 days (may be a finished course; confirm)", "info",
                "SELECT COUNT(*) " + ACTIVE_IN_FARM + " AND COALESCE(a.health_status, 'HEALTHY') = 'HEALTHY' "
                        + "AND EXISTS (SELECT 1 FROM health_records hr WHERE hr.animal_id = a.animal_id AND hr.record_type = 'TREATMENT' AND hr.event_at >= NOW() - INTERVAL '7 days')"));
        m.put("VACCINATION_IN_FUTURE", new Check("VACCINATION_IN_FUTURE",
                "Vaccination records dated in the future (data-entry error)", "error",
                "SELECT COUNT(*) FROM health_records hr JOIN animals a ON a.animal_id = hr.animal_id AND a.lifecycle_status = 'ACTIVE' "
                        + "LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id "
                        + "WHERE hr.record_type = 'VACCINATION' AND hr.event_at > NOW() + INTERVAL '1 hour' AND (? = TRUE OR b.farm_id = ?)"));
        m.put("VACCINATION_NO_VACCINE_NAME", new Check("VACCINATION_NO_VACCINE_NAME",
                "Vaccination records without a vaccine name (cannot be reported as FMD / LSD / other)", "warning",
                "SELECT COUNT(*) FROM health_records hr JOIN animals a ON a.animal_id = hr.animal_id AND a.lifecycle_status = 'ACTIVE' "
                        + "LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id "
                        + "WHERE hr.record_type = 'VACCINATION' AND COALESCE(hr.payload->>'vaccine', '') = '' AND (? = TRUE OR b.farm_id = ?)"));
        m.put("NO_ACTIVE_TAG", new Check("NO_ACTIVE_TAG",
                "Active animals without an active RFID tag (gates can never see them)", "warning",
                "SELECT COUNT(*) " + ACTIVE_IN_FARM + " AND NOT EXISTS (SELECT 1 FROM tag_assignments ta WHERE ta.animal_id = a.animal_id AND ta.valid_to IS NULL)"));
        m.put("RECORDS_FOR_INACTIVE_ANIMALS", new Check("RECORDS_FOR_INACTIVE_ANIMALS",
                "Health records of archived or sold animals (left out of every surveillance total)", "info",
                "SELECT COUNT(*) FROM health_records hr JOIN animals a ON a.animal_id = hr.animal_id AND a.lifecycle_status <> 'ACTIVE' "
                        + "LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id WHERE (? = TRUE OR b.farm_id = ?)"));
        return m;
    }
}
