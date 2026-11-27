package com.vinabeef.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The statements behind the Anomaly Alerts list: one page of rows and the number of rows that match.
 *
 * <p>Both share one WHERE, so the pager can never say "3 pages" for a list that has 2. The scope (who is asking) is
 * applied inside the statement; the filters can only narrow it. Rows are ordered newest first with the alert id as a
 * tie-breaker, so two alerts raised in the same instant cannot swap places between page 1 and page 2.
 */
final class AlertQueries {

    /** The most rows one request may ask for. */
    static final int MAX_PAGE_SIZE = 100;

    private AlertQueries() {
    }

    /** The page size to use for what was asked: at least 1, at most {@link #MAX_PAGE_SIZE}. */
    static int pageSize(int requested) {
        return Math.min(Math.max(1, requested), MAX_PAGE_SIZE);
    }

    static ReportQueries.Query count(ReportQueries.Scope scope, String state, String type, String search) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) ").append(from());
        where(sql, params, scope, state, type, search);
        return new ReportQueries.Query(sql.toString(), params);
    }

    static ReportQueries.Query page(ReportQueries.Scope scope, String state, String type, String search, int limit, int offset) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            SELECT
                al.alert_id,
                al.type,
                COALESCE(al.severity, 'HIGH') AS severity,
                al.animal_id,
                al.barn_id,
                b.code AS barn_code,
                f.farm_id,
                f.name AS farm_name,
                al.state,
                al.evidence::text AS evidence,
                al.raised_at,
                al.acknowledged_by,
                al.acknowledged_at,
                al.resolved_by,
                al.resolved_at,
                al.resolution_note
            """).append(from());
        where(sql, params, scope, state, type, search);
        sql.append(" ORDER BY al.raised_at DESC, al.alert_id DESC LIMIT ? OFFSET ?");
        params.add(Math.max(1, limit));
        params.add(Math.max(0, offset));
        return new ReportQueries.Query(sql.toString(), params);
    }

    private static String from() {
        return """
            FROM alerts al
            LEFT JOIN barns b ON al.barn_id = b.barn_id
            LEFT JOIN farms f ON b.farm_id = f.farm_id
            WHERE 1 = 1
            """;
    }

    private static void where(StringBuilder sql, List<Object> params, ReportQueries.Scope scope,
                              String state, String type, String search) {
        ReportQueries.scope(sql, params, scope, "b");
        String wantedState = word(state);
        if (!wantedState.isEmpty() && !wantedState.equals("ALL")) {
            sql.append(" AND al.state = ? ");
            params.add(wantedState);
        }
        String wantedType = word(type);
        if (!wantedType.isEmpty() && !wantedType.equals("ALL")) {
            sql.append(" AND al.type = ? ");
            params.add(wantedType);
        }
        String text = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        if (!text.isEmpty()) {
            String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append("""
                 AND (
                     LOWER(al.alert_id) LIKE ?
                     OR LOWER(COALESCE(al.animal_id, '')) LIKE ?
                     OR LOWER(COALESCE(b.code, al.barn_id, '')) LIKE ?
                     OR LOWER(COALESCE(al.evidence::text, '')) LIKE ?
                 )
                """);
            for (int i = 0; i < 4; i++) {
                params.add(pattern);
            }
        }
    }

    private static String word(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
