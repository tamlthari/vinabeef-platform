package com.vinabeef.web;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQL of the Herd list and the Gate Readers list: search, scope, sorting, and the IN / OUT pass counts.
 * Pure string building, tested on its own and run against PostgreSQL in the SQL checks.
 */
final class ListQueries {

    static final Map<String, String> CATTLE_SORT = Map.of(
            "created", "a.created_at",
            "id", "a.animal_id",
            "barn", "b.code",
            "farm", "f.name",
            "health", "COALESCE(a.health_status, 'HEALTHY')",
            "presence", "COALESCE(ps.presence_status, 'OUT_PASTURE')",
            "breed", "a.breed",
            "weight", "a.weight_kg");

    static final Map<String, String> READER_SORT = Map.of(
            "created", "g.created_at",
            "reader", "g.reader_name",
            "label", "g.label",
            "barn", "b.code",
            "farm", "f.name",
            "direction", "g.direction_mode",
            "status", "g.status",
            "last_seen", "last_seen",
            "in", "mc.in_count",
            "out", "mc.out_count");

    private ListQueries() {
    }

    // -------------------------------------------------------------------------------------------------------- cattle

    private static final String CATTLE_FROM = """
            FROM animals a
            LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
            LEFT JOIN farms f ON b.farm_id = f.farm_id
            LEFT JOIN presence_states ps ON ps.animal_id = a.animal_id
            WHERE a.lifecycle_status <> 'ARCHIVED'
        """;

    static ReportQueries.Query cattleCount(ReportQueries.Scope scope, String search) {
        ReportQueries.Query where = cattleWhere(scope, search);
        return new ReportQueries.Query("SELECT COUNT(*) " + where.sql(), where.params());
    }

    /**
     * One page of cattle in the requested order. The page is cut first (cheap, on the narrow rows), then the
     * pass counts and tags are looked up for those rows only.
     */
    static ReportQueries.Query cattlePage(ReportQueries.Scope scope, String search, String sort, String dir,
                                          int size, int offset) {
        ReportQueries.Query where = cattleWhere(scope, search);
        String order = SortSpec.orderBy(sort, dir, CATTLE_SORT, "created", "desc", "a.animal_id");
        List<Object> params = new ArrayList<>(where.params());
        params.add(size);
        params.add(offset);
        // Plain strings around the ORDER BY: a text block strips the space in front of its closing quotes.
        String head = "WITH page AS (\n"
                + "    SELECT a.animal_id, a.assigned_barn_id, a.lifecycle_status,\n"
                + "           COALESCE(a.health_status, 'HEALTHY') AS health_status, a.breed, a.weight_kg, a.created_at,\n"
                + "           ROW_NUMBER() OVER (ORDER BY " + order + ") AS rn\n";
        String tail = """
                ORDER BY rn
                LIMIT ? OFFSET ?
            )
            """;
        return new ReportQueries.Query(head + where.sql() + tail + """
            SELECT
                a.animal_id,
                a.assigned_barn_id,
                a.lifecycle_status,
                a.health_status,
                a.created_at,
                COALESCE(a.breed, 'Brahman Cross') AS breed,
                COALESCE(a.weight_kg, 420.0) AS weight_kg,
                b.code AS barn_code,
                f.farm_id,
                f.name AS farm_name,
                COALESCE(ps.presence_status, 'OUT_PASTURE') AS presence_status,
                (SELECT rt.epc_value
                 FROM tag_assignments ta
                 JOIN rfid_tags rt ON ta.tag_id = rt.tag_id
                 WHERE ta.animal_id = a.animal_id AND ta.valid_to IS NULL
                 ORDER BY ta.valid_from DESC LIMIT 1) AS active_tag_epc,
                (SELECT COUNT(*) FROM tag_assignments ta WHERE ta.animal_id = a.animal_id AND ta.valid_to IS NULL) AS active_tag_count,
                (SELECT COUNT(*) FROM health_records hr WHERE hr.animal_id = a.animal_id AND hr.record_type = 'VACCINATION') AS vaccination_count,
                COALESCE(mc.in_count, 0)  AS in_count,
                COALESCE(mc.out_count, 0) AS out_count,
                mc.last_pass_at,
                (SELECT mv.direction FROM movements mv WHERE mv.animal_id = a.animal_id
                 ORDER BY mv.occurred_at DESC LIMIT 1) AS last_pass_direction
            FROM page a
            LEFT JOIN barns b ON a.assigned_barn_id = b.barn_id
            LEFT JOIN farms f ON b.farm_id = f.farm_id
            LEFT JOIN presence_states ps ON a.animal_id = ps.animal_id
            LEFT JOIN LATERAL (
                SELECT COUNT(*) FILTER (WHERE mv.direction = 'IN')  AS in_count,
                       COUNT(*) FILTER (WHERE mv.direction = 'OUT') AS out_count,
                       MAX(mv.occurred_at) AS last_pass_at
                FROM movements mv WHERE mv.animal_id = a.animal_id
            ) mc ON TRUE
            ORDER BY a.rn
        """, params);
    }

    private static ReportQueries.Query cattleWhere(ReportQueries.Scope scope, String search) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(CATTLE_FROM);
        ReportQueries.scope(sql, params, scope, "b");
        String trimmed = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.isEmpty()) {
            String pattern = "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append("""
                AND (
                    LOWER(a.animal_id) LIKE ?
                    OR LOWER(COALESCE(a.breed, '')) LIKE ?
                    OR LOWER(COALESCE(a.health_status, '')) LIKE ?
                    OR LOWER(COALESCE(b.code, '')) LIKE ?
                    OR LOWER(COALESCE(f.name, '')) LIKE ?
                    OR EXISTS (
                        SELECT 1 FROM tag_assignments ta
                        JOIN rfid_tags rt ON ta.tag_id = rt.tag_id
                        WHERE ta.animal_id = a.animal_id AND LOWER(rt.epc_value) LIKE ?
                    )
                )
            """);
            for (int i = 0; i < 6; i++) {
                params.add(pattern);
            }
        }
        return new ReportQueries.Query(sql.toString(), params);
    }

    // ------------------------------------------------------------------------------------------------ recent movements

    /**
     * The newest gate passages in scope, for the dashboard's "Gate Passage Stream".
     * Carries the names people recognise (the reader names printed on the hardware and in the Safetrust console, the gate
     * label, the barn code), not only the gate's internal key (e.g. "GATE-3A9F2C"), which means nothing outside the database.
     * The scope is applied inside the statement, before LIMIT: filtering the newest N rows afterwards left a farm manager
     * with an empty stream whenever the newest N passages belonged to another farm.
     */
    static ReportQueries.Query recentMovements(ReportQueries.Scope scope, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            SELECT mv.movement_id, mv.animal_id, mv.gate_id, mv.direction, mv.detection_method, mv.occurred_at,
                   g.label       AS gate_label,
                   g.reader_name AS gate_reader,
                   sr.reader_name AS read_reader,
                   fr.reader_name AS first_reader,
                   b.code        AS barn_code
            FROM movements mv
            LEFT JOIN gates g  ON g.gate_id = mv.gate_id
            LEFT JOIN barns b  ON b.barn_id = g.barn_id
            LEFT JOIN rfid_read_events sr ON sr.event_id = mv.source_event_id
            LEFT JOIN rfid_read_events fr ON fr.event_id = mv.first_event_id
            WHERE 1 = 1
        """);
        ReportQueries.scope(sql, params, scope, "b");
        sql.append(" ORDER BY mv.occurred_at DESC, mv.movement_id DESC LIMIT ? ");
        params.add(limit);
        return new ReportQueries.Query(sql.toString(), params);
    }

    // ------------------------------------------------------------------------------------------------------- readers

    /** All active readers in scope. {@code dayStart}/{@code dayEnd} bound "today" for the today_in / today_out counts. */
    static ReportQueries.Query readers(ReportQueries.Scope scope, String search, String sort, String dir,
                                       Instant dayStart, Instant dayEnd) {
        List<Object> params = new ArrayList<>();
        Timestamp from = Timestamp.from(dayStart);
        Timestamp to = Timestamp.from(dayEnd);
        // the four "today" bounds appear first in the statement, in this order
        params.add(from);
        params.add(to);
        params.add(from);
        params.add(to);

        StringBuilder sql = new StringBuilder("""
            SELECT
                g.gate_id,
                g.barn_id,
                g.label,
                g.reader_name,
                g.direction_mode,
                g.created_at,
                COALESCE(g.firmware_version, '1.58.1998') AS firmware_version,
                COALESCE(g.status, 'ONLINE') AS status,
                b.code AS barn_code,
                f.farm_id,
                f.name AS farm_name,
                (SELECT gr.reader_name FROM gate_readers gr WHERE gr.gate_id = g.gate_id AND gr.side = 'OUTER') AS outer_reader_name,
                (SELECT MAX(re.observed_at) FROM rfid_read_events re
                  WHERE re.read_source = 'SAFETRUST'   -- a USB read on the barn gateway says nothing about the wall reader
                    AND re.reader_name IN (SELECT g.reader_name
                                           UNION SELECT gr.reader_name FROM gate_readers gr WHERE gr.gate_id = g.gate_id)) AS last_seen,
                (SELECT gr.gate_id FROM gate_readers gr WHERE gr.reader_name = g.reader_name AND gr.gate_id <> g.gate_id) AS linked_to_gate_id,
                (SELECT gr.side FROM gate_readers gr WHERE gr.reader_name = g.reader_name AND gr.gate_id <> g.gate_id) AS linked_side,
                (SELECT lg.label FROM gate_readers gr JOIN gates lg ON lg.gate_id = gr.gate_id
                  WHERE gr.reader_name = g.reader_name AND gr.gate_id <> g.gate_id) AS linked_to_label,
                COALESCE(mc.in_count, 0)  AS in_count,
                COALESCE(mc.out_count, 0) AS out_count,
                COALESCE(mc.today_in, 0)  AS today_in,
                COALESCE(mc.today_out, 0) AS today_out,
                COALESCE(mc.in_count, 0) + COALESCE(mc.out_count, 0) AS passage_count
            FROM gates g
            LEFT JOIN barns b ON g.barn_id = b.barn_id
            LEFT JOIN farms f ON b.farm_id = f.farm_id
            LEFT JOIN LATERAL (
                SELECT COUNT(*) FILTER (WHERE m.direction = 'IN')  AS in_count,
                       COUNT(*) FILTER (WHERE m.direction = 'OUT') AS out_count,
                       COUNT(*) FILTER (WHERE m.direction = 'IN'  AND m.occurred_at >= ? AND m.occurred_at < ?) AS today_in,
                       COUNT(*) FILTER (WHERE m.direction = 'OUT' AND m.occurred_at >= ? AND m.occurred_at < ?) AS today_out
                FROM movements m WHERE m.gate_id = g.gate_id
            ) mc ON TRUE
            WHERE g.retired_at IS NULL
        """);
        ReportQueries.scope(sql, params, scope, "b");
        String trimmed = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.isEmpty()) {
            String pattern = "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append("""
                AND (
                    LOWER(g.reader_name) LIKE ?
                    OR LOWER(g.label) LIKE ?
                    OR LOWER(g.gate_id) LIKE ?
                    OR LOWER(COALESCE(b.code, '')) LIKE ?
                    OR LOWER(COALESCE(f.name, '')) LIKE ?
                )
            """);
            for (int i = 0; i < 5; i++) {
                params.add(pattern);
            }
        }
        sql.append(" ORDER BY ").append(SortSpec.orderBy(sort, dir, READER_SORT, "created", "desc", "g.gate_id"));
        return new ReportQueries.Query(sql.toString(), params);
    }
}
