package com.vinabeef.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The SQL behind the health reports (vaccinations, treatments, health history, quarantine status) and the dashboard's
 * health panel. Pure string building like {@link ReportQueries}: every value from the request is a bind parameter, the
 * ORDER BY comes from a fixed table, and the person's scope is always applied first.
 *
 * <p>What counts as an event (one condition each, on the health_records table aliased {@code hr}):
 * <ul>
 *   <li><b>Vaccination</b>: a VACCINATION record.</li>
 *   <li><b>Treatment</b>: a TREATMENT record, or any record that left the animal UNDER_TREATMENT.</li>
 *   <li><b>Quarantine</b>: a QUARANTINE record, or any record that put the animal in QUARANTINE (the web form and the
 *       field app write the new status in {@code payload.resulting_status} of whatever record type was chosen).</li>
 *   <li><b>Health check</b>: a HEALTH_CHECK record.</li>
 * </ul>
 * One record can be in two of these (a treatment that ended in quarantine); "All" lists every record once.
 */
final class HealthReportQueries {

    /** A kind of health event. {@code condition} is a fixed SQL fragment, never built from a request. */
    enum EventKind {
        ALL("1 = 1"),
        VACCINATION("hr.record_type = 'VACCINATION'"),
        TREATMENT("(hr.record_type = 'TREATMENT' OR hr.payload->>'resulting_status' = 'UNDER_TREATMENT')"),
        QUARANTINE("(hr.record_type = 'QUARANTINE' OR hr.payload->>'resulting_status' = 'QUARANTINE')"),
        HEALTH_CHECK("hr.record_type = 'HEALTH_CHECK'");

        final String condition;

        EventKind(String condition) {
            this.condition = condition;
        }

        /** The kind a request names; blank or unknown means ALL. */
        static EventKind of(String name) {
            String hit = ListFilters.oneOf(name, "ALL", "VACCINATION", "TREATMENT", "QUARANTINE", "HEALTH_CHECK");
            return hit == null ? ALL : valueOf(hit);
        }
    }

    /** Words of the "result" filter. */
    static final String[] STATUSES = {"HEALTHY", "UNDER_TREATMENT", "QUARANTINE"};

    /**
     * Filters of the event reports. {@code text} is searched in the vaccine / medication / diagnosis, batch and notes;
     * {@code status} is the health status the record left the animal in.
     */
    record EventFilter(String farmId, String barnId, String animal, String text, String status,
                       Instant from, Instant to, String sort, String dir) {
    }

    /** Filters of the current-status report (animals that are in quarantine or under treatment right now). */
    record StatusFilter(String farmId, String barnId, String animal, String status, String presence, String sort, String dir) {
    }

    // ---- fragments of payload keys. The web form, the field app and the seed data wrote different keys for the same thing,
    // ---- so each column reads the first key that is present.

    private static String title(String a) {
        return "COALESCE(" + a + ".payload->>'title', " + a + ".payload->>'vaccine', " + a + ".payload->>'condition', "
                + a + ".payload->>'status')";
    }

    private static String product(String a) {
        return "COALESCE(" + a + ".payload->>'vaccine', " + a + ".payload->>'medication', " + a + ".payload->>'medication_or_batch')";
    }

    private static String batch(String a) {
        return "COALESCE(" + a + ".payload->>'batch', " + a + ".payload->>'batch_number')";
    }

    private static String dose(String a) {
        return "COALESCE(" + a + ".payload->>'dosage', " + a + ".payload->>'dose_ml')";
    }

    private static String outcome(String a) {
        return "COALESCE(" + a + ".payload->>'resulting_status', " + a + ".payload->>'outcome')";
    }

    static final Map<String, String> EVENT_SORT = Map.of(
            "time", "hr.event_at",
            "animal", "hr.animal_id",
            "type", "hr.record_type",
            "product", product("hr"),
            "barn", "b.code",
            "farm", "f.name",
            "by", "hr.administered_by",
            "result", outcome("hr"));

    static final Map<String, String> STATUS_SORT = Map.of(
            "since", "ep.since",
            "animal", "a.animal_id",
            "barn", "b.code",
            "farm", "f.name",
            "status", "a.health_status",
            "presence", "COALESCE(ps.presence_status, 'OUT_PASTURE')");

    private HealthReportQueries() {
    }

    // ------------------------------------------------------------------------------------------------------ events

    private static final String EVENT_FROM = """
            FROM health_records hr
            JOIN animals a ON a.animal_id = hr.animal_id
            LEFT JOIN barns b ON b.barn_id = a.assigned_barn_id
            LEFT JOIN farms f ON f.farm_id = b.farm_id
            WHERE hr.event_at >= ? AND hr.event_at < ?
        """;

    /** One page of events, newest first unless another column is chosen. */
    static ReportQueries.Query eventPage(ReportQueries.Scope scope, EventKind kind, EventFilter f, int size, int offset) {
        ReportQueries.Query where = eventWhere(scope, kind, f);
        String order = SortSpec.orderBy(f.sort(), f.dir(), EVENT_SORT, "time", "desc", "hr.health_id");
        List<Object> params = new ArrayList<>(where.params());
        params.add(size);
        params.add(offset);
        return new ReportQueries.Query("""
            SELECT hr.health_id, hr.event_at, hr.record_type, hr.administered_by, hr.animal_id,
                   COALESCE(a.health_status, 'HEALTHY') AS current_status,
                   b.barn_id, b.code AS barn_code, f.farm_id, f.name AS farm_name,
        """
                + "       " + title("hr") + " AS title,\n"
                + "       " + product("hr") + " AS product,\n"
                + "       " + batch("hr") + " AS batch,\n"
                + "       " + dose("hr") + " AS dose,\n"
                + "       " + outcome("hr") + " AS resulting_status,\n"
                + "       hr.payload->>'next_due_date' AS next_due_date,\n"
                + "       hr.payload->>'notes' AS notes\n"
                + where.sql() + " ORDER BY " + order + " LIMIT ? OFFSET ? ", params);
    }

    /** Count of events and of the animals they are about. */
    static ReportQueries.Query eventTotals(ReportQueries.Scope scope, EventKind kind, EventFilter f) {
        ReportQueries.Query where = eventWhere(scope, kind, f);
        return new ReportQueries.Query("SELECT COUNT(*) AS total, COUNT(DISTINCT hr.animal_id) AS animals " + where.sql(),
                where.params());
    }

    private static ReportQueries.Query eventWhere(ReportQueries.Scope scope, EventKind kind, EventFilter f) {
        List<Object> params = new ArrayList<>();
        params.add(ReportQueries.ts(f.from()));
        params.add(ReportQueries.ts(f.to()));
        StringBuilder sql = new StringBuilder(EVENT_FROM);
        ReportQueries.scope(sql, params, scope, "b");
        sql.append(" AND ").append(kind.condition).append(' ');
        if (ReportQueries.isSet(f.farmId()) && !"ALL".equalsIgnoreCase(f.farmId().trim())) {
            sql.append(" AND b.farm_id = ? ");
            params.add(f.farmId().trim());
        }
        if (ReportQueries.isSet(f.barnId())) {
            sql.append(" AND b.barn_id = ? ");
            params.add(f.barnId().trim());
        }
        if (ReportQueries.isSet(f.animal())) {
            sql.append(" AND LOWER(hr.animal_id) LIKE ? ");
            params.add(ReportQueries.like(f.animal()));
        }
        if (ReportQueries.isSet(f.text())) {
            sql.append(" AND LOWER(CONCAT_WS(' ', ").append(title("hr")).append(", ").append(product("hr")).append(", ")
                    .append(batch("hr")).append(", hr.payload->>'notes')) LIKE ? ");
            params.add(ReportQueries.like(f.text()));
        }
        String status = ListFilters.oneOf(f.status(), STATUSES);
        if (status != null) {
            sql.append(" AND ").append(outcome("hr")).append(" = ? ");
            params.add(status);
        }
        return new ReportQueries.Query(sql.toString(), params);
    }

    // ------------------------------------------------------------------------------------------------ current status

    /**
     * What makes an event "the reason for the animal's current status": it left the animal in that status, or it is of the
     * kind that does (a QUARANTINE record for a quarantined animal, a TREATMENT record for one under treatment).
     */
    private static String matchesStatus(String hr) {
        return "(" + hr + ".payload->>'resulting_status' = a.health_status"
                + " OR (a.health_status = 'QUARANTINE' AND " + hr + ".record_type = 'QUARANTINE')"
                + " OR (a.health_status = 'UNDER_TREATMENT' AND " + hr + ".record_type = 'TREATMENT'))";
    }

    /**
     * Animals that are in quarantine and / or under treatment now, with when it started (the first matching record after the
     * animal was last recorded healthy) and the latest matching record for the reason.
     */
    private static final String STATUS_FROM = """
            FROM animals a
            LEFT JOIN barns b ON b.barn_id = a.assigned_barn_id
            LEFT JOIN farms f ON f.farm_id = b.farm_id
            LEFT JOIN presence_states ps ON ps.animal_id = a.animal_id
            LEFT JOIN LATERAL (
                SELECT hr.event_at, hr.administered_by, hr.payload
                FROM health_records hr
                WHERE hr.animal_id = a.animal_id AND %s
                ORDER BY hr.event_at DESC LIMIT 1
            ) lr ON TRUE
            LEFT JOIN LATERAL (
                SELECT MIN(hr.event_at) AS since
                FROM health_records hr
                WHERE hr.animal_id = a.animal_id AND %s
                  AND hr.event_at > COALESCE((SELECT MAX(h2.event_at) FROM health_records h2
                                              WHERE h2.animal_id = a.animal_id AND h2.payload->>'resulting_status' = 'HEALTHY'),
                                             TIMESTAMPTZ '-infinity')
            ) ep ON TRUE
            WHERE a.lifecycle_status <> 'ARCHIVED'
        """.formatted(matchesStatus("hr"), matchesStatus("hr"));

    static ReportQueries.Query statusPage(ReportQueries.Scope scope, StatusFilter f, int size, int offset) {
        ReportQueries.Query where = statusWhere(scope, f);
        String order = SortSpec.orderBy(f.sort(), f.dir(), STATUS_SORT, "since", "asc", "a.animal_id");
        List<Object> params = new ArrayList<>(where.params());
        params.add(size);
        params.add(offset);
        return new ReportQueries.Query("""
            SELECT a.animal_id, a.health_status, COALESCE(a.quarantine_notes, '') AS quarantine_notes,
                   b.barn_id, b.code AS barn_code, f.farm_id, f.name AS farm_name,
                   COALESCE(ps.presence_status, 'OUT_PASTURE') AS presence_status,
                   ep.since,
                   FLOOR(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - ep.since)) / 86400)::int AS days,
                   lr.event_at AS last_event_at, lr.administered_by,
        """
                + "       " + title("lr") + " AS title,\n"
                + "       " + product("lr") + " AS product,\n"
                + "       lr.payload->>'notes' AS notes\n"
                + where.sql() + " ORDER BY " + order + " LIMIT ? OFFSET ? ", params);
    }

    static ReportQueries.Query statusTotals(ReportQueries.Scope scope, StatusFilter f) {
        ReportQueries.Query where = statusWhere(scope, f);
        return new ReportQueries.Query("""
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE a.health_status = 'QUARANTINE') AS quarantine_count,
                   COUNT(*) FILTER (WHERE a.health_status = 'UNDER_TREATMENT') AS treatment_count,
                   COUNT(*) FILTER (WHERE COALESCE(ps.presence_status, 'OUT_PASTURE') = 'OUT_PASTURE') AS at_pasture_count
        """ + where.sql(), where.params());
    }

    private static ReportQueries.Query statusWhere(ReportQueries.Scope scope, StatusFilter f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(STATUS_FROM);
        ReportQueries.scope(sql, params, scope, "b");
        String status = f.status() == null ? "" : f.status().trim().toUpperCase(Locale.ROOT);
        if (status.equals("UNDER_TREATMENT")) {
            sql.append(" AND a.health_status = 'UNDER_TREATMENT' ");
        } else if (status.equals("BOTH")) {
            sql.append(" AND a.health_status IN ('QUARANTINE', 'UNDER_TREATMENT') ");
        } else {
            sql.append(" AND a.health_status = 'QUARANTINE' ");     // the default: quarantine status
        }
        if (ReportQueries.isSet(f.farmId()) && !"ALL".equalsIgnoreCase(f.farmId().trim())) {
            sql.append(" AND b.farm_id = ? ");
            params.add(f.farmId().trim());
        }
        if (ReportQueries.isSet(f.barnId())) {
            sql.append(" AND b.barn_id = ? ");
            params.add(f.barnId().trim());
        }
        if (ReportQueries.isSet(f.animal())) {
            sql.append(" AND LOWER(a.animal_id) LIKE ? ");
            params.add(ReportQueries.like(f.animal()));
        }
        String presence = ListFilters.oneOf(f.presence(), "IN_BARN", "OUT_PASTURE");
        if (presence != null) {
            sql.append(" AND COALESCE(ps.presence_status, 'OUT_PASTURE') = ? ");
            params.add(presence);
        }
        return new ReportQueries.Query(sql.toString(), params);
    }

    // -------------------------------------------------------------------------------------------------- dashboard

    /** How many animals are in quarantine / under treatment now, and how many of the quarantined are out at pasture. */
    static ReportQueries.Query dashboardNow(ReportQueries.Scope scope) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*) FILTER (WHERE a.health_status = 'QUARANTINE') AS quarantine_now,
                   COUNT(*) FILTER (WHERE a.health_status = 'UNDER_TREATMENT') AS treatment_now,
                   COUNT(*) FILTER (WHERE a.health_status = 'QUARANTINE'
                                      AND COALESCE(ps.presence_status, 'OUT_PASTURE') = 'OUT_PASTURE') AS quarantine_at_pasture
            FROM animals a
            LEFT JOIN barns b ON b.barn_id = a.assigned_barn_id
            LEFT JOIN presence_states ps ON ps.animal_id = a.animal_id
            WHERE a.lifecycle_status <> 'ARCHIVED'
        """);
        ReportQueries.scope(sql, params, scope, "b");
        return new ReportQueries.Query(sql.toString(), params);
    }

    /** How many events of each kind were recorded since {@code since}. */
    static ReportQueries.Query dashboardEvents(ReportQueries.Scope scope, Instant since) {
        List<Object> params = new ArrayList<>();
        params.add(ReportQueries.ts(since));
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS all_events,\n"
                + "       COUNT(*) FILTER (WHERE " + EventKind.VACCINATION.condition + ") AS vaccinations,\n"
                + "       COUNT(*) FILTER (WHERE " + EventKind.TREATMENT.condition + ") AS treatments,\n"
                + "       COUNT(*) FILTER (WHERE " + EventKind.QUARANTINE.condition + ") AS quarantine_events\n"
                + "FROM health_records hr\n"
                + "JOIN animals a ON a.animal_id = hr.animal_id\n"
                + "LEFT JOIN barns b ON b.barn_id = a.assigned_barn_id\n"
                + "WHERE hr.event_at >= ?\n");
        ReportQueries.scope(sql, params, scope, "b");
        return new ReportQueries.Query(sql.toString(), params);
    }
}
