package com.vinabeef.web;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The SQL behind the Reporting page. Pure string building, so the exact statements are unit-tested and also run
 * against a real PostgreSQL in the test suite.
 *
 * <p>Every value from the request goes in as a bind parameter. The only text that is not a parameter is the ORDER BY
 * column, which comes from the fixed table in {@link SortSpec}.
 */
final class ReportQueries {

    record Query(String sql, List<Object> params) {
    }

    /** What the signed-in person may see. */
    record Scope(Kind kind, String farmId, List<String> barnIds) {
        /** How each kind of person is kept to the rows they may see. Adding a kind means adding a constant. */
        enum Kind {
            ALL {
                @Override
                void restrict(StringBuilder sql, List<Object> params, Scope scope, String barnAlias) {
                    // sees everything: no condition
                }
            },
            FARM {
                @Override
                void restrict(StringBuilder sql, List<Object> params, Scope scope, String barnAlias) {
                    sql.append(" AND ").append(barnAlias).append(".farm_id = ? ");
                    params.add(scope.farmId());
                }
            },
            BARNS {
                @Override
                void restrict(StringBuilder sql, List<Object> params, Scope scope, String barnAlias) {
                    sql.append(" AND ").append(barnAlias).append(".barn_id IN (")
                            .append(String.join(",", Collections.nCopies(scope.barnIds().size(), "?"))).append(") ");
                    params.addAll(scope.barnIds());
                }
            },
            /** No scope at all: a condition that is never true. */
            NONE {
                @Override
                void restrict(StringBuilder sql, List<Object> params, Scope scope, String barnAlias) {
                    sql.append(" AND 1 = 0 ");
                }
            };

            abstract void restrict(StringBuilder sql, List<Object> params, Scope scope, String barnAlias);
        }

        static final Scope ALL = new Scope(Kind.ALL, null, List.of());
        static final Scope NONE = new Scope(Kind.NONE, null, List.of());

        static Scope farm(String farmId) {
            return farmId == null ? NONE : new Scope(Kind.FARM, farmId, List.of());
        }

        static Scope barns(List<String> barnIds) {
            return barnIds == null || barnIds.isEmpty() ? NONE : new Scope(Kind.BARNS, null, List.copyOf(barnIds));
        }

        boolean seesNothing() {
            return kind == Kind.NONE;
        }
    }

    record PassageFilter(String farmId, String barnId, String reader, String animal, String direction, String method,
                         Instant from, Instant to, String sort, String dir) {
    }

    record ReadFilter(String farmId, String barnId, String reader, String animal, String event, String result,
                      Instant from, Instant to, String sort, String dir) {
    }

    static final Map<String, String> PASSAGE_SORT = Map.of(
            "time", "mv.occurred_at",
            "animal", "mv.animal_id",
            "reader", "g.reader_name",
            "barn", "b.code",
            "farm", "f.name",
            "direction", "mv.direction",
            "method", "mv.detection_method");

    static final Map<String, String> READ_SORT = Map.of(
            "time", "re.observed_at",
            "reader", "re.reader_name",
            "animal", "COALESCE(re.user_name, re.card_uuid)",
            "event", "re.gate_action",
            "barn", "b.code");

    private ReportQueries() {
    }

    // ------------------------------------------------------------------------------------------------ herd balance

    /** One row per barn: herd, where it is now, and the gate passes inside the period. */
    static Query herdBalance(Scope scope, String farmId, Instant from, Instant to) {
        List<Object> params = new ArrayList<>();
        params.add(ts(from));
        params.add(ts(to));
        StringBuilder sql = new StringBuilder("""
            SELECT f.farm_id, f.name AS farm_name, b.barn_id, b.code AS barn_code, b.capacity,
                   c.assigned_count, c.in_barn_count, c.out_pasture_count, c.unseen_count,
                   COALESCE(m.in_count, 0)  AS in_count,
                   COALESCE(m.out_count, 0) AS out_count
            FROM barns b
            JOIN farms f ON f.farm_id = b.farm_id
            JOIN v_barn_counts c ON c.barn_id = b.barn_id
            LEFT JOIN (
                SELECT g.barn_id,
                       COUNT(*) FILTER (WHERE mv.direction = 'IN')  AS in_count,
                       COUNT(*) FILTER (WHERE mv.direction = 'OUT') AS out_count
                FROM movements mv
                JOIN gates g ON g.gate_id = mv.gate_id
                WHERE mv.occurred_at >= ? AND mv.occurred_at < ?
                GROUP BY g.barn_id
            ) m ON m.barn_id = b.barn_id
            WHERE 1 = 1
        """);
        scope(sql, params, scope, "b");
        if (isSet(farmId) && !"ALL".equalsIgnoreCase(farmId.trim())) {
            sql.append(" AND b.farm_id = ? ");
            params.add(farmId.trim());
        }
        sql.append(" ORDER BY f.farm_id, b.code ");
        return new Query(sql.toString(), params);
    }

    // ---------------------------------------------------------------------------------------------- gate passages

    private static final String PASSAGE_FROM = """
            FROM movements mv
            JOIN gates g ON g.gate_id = mv.gate_id
            JOIN barns b ON b.barn_id = g.barn_id
            JOIN farms f ON f.farm_id = b.farm_id
            LEFT JOIN rfid_read_events re ON re.event_id = mv.source_event_id
            WHERE mv.occurred_at >= ? AND mv.occurred_at < ?
        """;

    static Query passagePage(Scope scope, PassageFilter f, int size, int offset) {
        Query where = passageWhere(scope, f);
        String order = SortSpec.orderBy(f.sort(), f.dir(), PASSAGE_SORT, "time", "desc", "mv.movement_id");
        List<Object> params = new ArrayList<>(where.params());
        params.add(size);
        params.add(offset);
        return new Query("""
            SELECT mv.movement_id, mv.occurred_at, mv.animal_id, mv.direction, mv.detection_method,
                   g.gate_id, g.label AS gate_label, g.reader_name AS gate_reader,
                   re.reader_name AS read_reader, re.card_uuid,
                   b.barn_id, b.code AS barn_code, f.farm_id, f.name AS farm_name
        """ + where.sql() + " ORDER BY " + order + " LIMIT ? OFFSET ? ", params);
    }

    static Query passageTotals(Scope scope, PassageFilter f) {
        Query where = passageWhere(scope, f);
        return new Query("""
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE mv.direction = 'IN')  AS in_count,
                   COUNT(*) FILTER (WHERE mv.direction = 'OUT') AS out_count
        """ + where.sql(), where.params());
    }

    static Query passageExport(Scope scope, PassageFilter f, int max) {
        Query page = passagePage(scope, f, max, 0);
        return page;
    }

    private static Query passageWhere(Scope scope, PassageFilter f) {
        List<Object> params = new ArrayList<>();
        params.add(ts(f.from()));
        params.add(ts(f.to()));
        StringBuilder sql = new StringBuilder(PASSAGE_FROM);
        scope(sql, params, scope, "b");
        if (isSet(f.farmId()) && !"ALL".equalsIgnoreCase(f.farmId().trim())) {
            sql.append(" AND b.farm_id = ? ");
            params.add(f.farmId().trim());
        }
        if (isSet(f.barnId())) {
            sql.append(" AND b.barn_id = ? ");
            params.add(f.barnId().trim());
        }
        if (isSet(f.reader())) {
            sql.append(" AND (LOWER(g.reader_name) LIKE ? OR LOWER(g.label) LIKE ? OR LOWER(g.gate_id) LIKE ? "
                    + "OR LOWER(COALESCE(re.reader_name, '')) LIKE ?) ");
            String p = like(f.reader());
            params.add(p);
            params.add(p);
            params.add(p);
            params.add(p);
        }
        if (isSet(f.animal())) {
            sql.append(" AND LOWER(mv.animal_id) LIKE ? ");
            params.add(like(f.animal()));
        }
        String direction = upper(f.direction());
        if ("IN".equals(direction) || "OUT".equals(direction)) {
            sql.append(" AND mv.direction = ? ");
            params.add(direction);
        }
        if (isSet(f.method())) {
            sql.append(" AND mv.detection_method = ? ");
            params.add(upper(f.method()));
        }
        return new Query(sql.toString(), params);
    }

    // ---------------------------------------------------------------------------------------------- raw RFID reads

    private static final String READ_FROM = """
            FROM rfid_read_events re
            LEFT JOIN movements mv ON mv.source_event_id = re.event_id
            LEFT JOIN gate_readers gr ON gr.reader_name = re.reader_name
            LEFT JOIN gates g ON g.gate_id = COALESCE(gr.gate_id,
                    (SELECT g2.gate_id FROM gates g2 WHERE g2.reader_name = re.reader_name AND g2.retired_at IS NULL LIMIT 1))
            LEFT JOIN barns b ON b.barn_id = g.barn_id
            LEFT JOIN farms f ON f.farm_id = b.farm_id
            WHERE re.observed_at >= ? AND re.observed_at < ?
        """;

    static Query readPage(Scope scope, ReadFilter f, int size, int offset) {
        Query where = readWhere(scope, f);
        String order = SortSpec.orderBy(f.sort(), f.dir(), READ_SORT, "time", "desc", "re.event_id");
        List<Object> params = new ArrayList<>(where.params());
        params.add(size);
        params.add(offset);
        return new Query("""
            SELECT re.event_id, re.observed_at, re.reader_name, re.card_uuid, re.user_name, re.gate_action,
                   g.label AS gate_label, b.code AS barn_code, f.name AS farm_name,
                   mv.direction AS movement_direction
        """ + where.sql() + " ORDER BY " + order + " LIMIT ? OFFSET ? ", params);
    }

    static Query readTotals(Scope scope, ReadFilter f) {
        Query where = readWhere(scope, f);
        return new Query("""
            SELECT COUNT(*) AS total,
                   COUNT(mv.movement_id) AS with_movement
        """ + where.sql(), where.params());
    }

    private static Query readWhere(Scope scope, ReadFilter f) {
        List<Object> params = new ArrayList<>();
        params.add(ts(f.from()));
        params.add(ts(f.to()));
        StringBuilder sql = new StringBuilder(READ_FROM);
        scope(sql, params, scope, "b");
        if (isSet(f.farmId()) && !"ALL".equalsIgnoreCase(f.farmId().trim())) {
            sql.append(" AND b.farm_id = ? ");
            params.add(f.farmId().trim());
        }
        if (isSet(f.barnId())) {
            sql.append(" AND b.barn_id = ? ");
            params.add(f.barnId().trim());
        }
        if (isSet(f.reader())) {
            sql.append(" AND LOWER(re.reader_name) LIKE ? ");
            params.add(like(f.reader()));
        }
        if (isSet(f.animal())) {
            sql.append(" AND (LOWER(COALESCE(re.user_name, '')) LIKE ? OR LOWER(COALESCE(re.card_uuid, '')) LIKE ?) ");
            String p = like(f.animal());
            params.add(p);
            params.add(p);
        }
        if (isSet(f.event())) {
            sql.append(" AND LOWER(COALESCE(re.gate_action, '')) LIKE ? ");
            params.add(like(f.event()));
        }
        String result = f.result() == null ? "" : f.result().trim().toLowerCase(Locale.ROOT);
        if (result.equals("movement")) {
            sql.append(" AND mv.movement_id IS NOT NULL ");
        } else if (result.equals("none")) {
            sql.append(" AND mv.movement_id IS NULL ");
        }
        return new Query(sql.toString(), params);
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** Adds the restriction for the alias of the barns table. A person with no scope gets a condition that is never true. */
    static void scope(StringBuilder sql, List<Object> params, Scope scope, String barnAlias) {
        scope.kind().restrict(sql, params, scope, barnAlias);
    }

    static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        // a spreadsheet would run a cell that starts with one of these as a formula
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    private static String like(String s) {
        return "%" + s.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
