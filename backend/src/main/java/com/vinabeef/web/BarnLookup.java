package com.vinabeef.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which barn a person means when they type something in the field app.
 *
 * <p>A barn has two names: the <b>barn id</b>, the key every table points at (it never changes and people are not meant
 * to read it), and the <b>barn code</b>, the name printed on the barn and shown everywhere on the web portal. The field
 * app used to ask for the id, so the same barn was "DL-BARN-011" on the screen at the office and "BARN-DL-011" on the
 * phone. People now type the code; the id is still accepted, so a phone that saved one keeps working.
 *
 * <p>A code is unique inside one facility only, so two facilities may both have a "BARN-01". Then the code alone is
 * ambiguous and the barn is named "BARN-01 - Facility name" (see {@link #label}); the answer says so instead of picking one.
 * Order of matching: barn id (exactly, then ignoring case), the label, the code. The first rule with exactly one barn wins.
 */
final class BarnLookup {

    enum Kind { ALL, FOUND, NONE, AMBIGUOUS }

    /** {@code barnId} and {@code label} are set for FOUND; {@code message} for AMBIGUOUS. */
    record Result(Kind kind, String barnId, String label, String message) {
        static Result all() { return new Result(Kind.ALL, "ALL", "ALL", ""); }
        static Result none() { return new Result(Kind.NONE, "", "", ""); }
    }

    private BarnLookup() {
    }

    /**
     * @param requested what the person typed; empty means "my barn" (the first of {@code barns}), ALL means every barn
     * @param barns     the barns this person may see, as rows with {@code barn_id}, {@code code} and {@code farm_name}
     */
    static Result resolve(String requested, List<Map<String, Object>> barns) {
        String wanted = requested == null ? "" : requested.trim();
        if ("ALL".equalsIgnoreCase(wanted)) {
            return Result.all();
        }
        if (barns.isEmpty()) {
            return Result.none();
        }
        if (wanted.isEmpty()) {
            return found(barns.get(0), barns);
        }
        Result hit = single(barns, wanted, "barn_id", true);
        if (hit == null) hit = single(barns, wanted, "barn_id", false);
        if (hit == null) {
            List<Map<String, Object>> byLabel = new ArrayList<>();
            for (Map<String, Object> b : barns) {
                if (wanted.equalsIgnoreCase(longLabel(b))) byLabel.add(b);
            }
            if (byLabel.size() == 1) hit = found(byLabel.get(0), barns);
        }
        if (hit == null) {
            List<Map<String, Object>> byCode = new ArrayList<>();
            for (Map<String, Object> b : barns) {
                if (wanted.equalsIgnoreCase(text(b, "code"))) byCode.add(b);
            }
            if (byCode.size() == 1) {
                hit = found(byCode.get(0), barns);
            } else if (byCode.size() > 1) {
                return new Result(Kind.AMBIGUOUS, "", "", "The barn code " + text(byCode.get(0), "code")
                        + " is used in more than one facility. Type the barn with its facility, for example \""
                        + longLabel(byCode.get(0)) + "\", or ALL.");
            }
        }
        return hit != null ? hit : Result.none();
    }

    /** What to show for a barn: its code, with the facility added when another barn of the same list has that code. */
    static String label(Map<String, Object> barn, List<Map<String, Object>> barns) {
        String code = text(barn, "code");
        for (Map<String, Object> other : barns) {
            if (other != barn && code.equalsIgnoreCase(text(other, "code"))
                    && !text(other, "barn_id").equals(text(barn, "barn_id"))) {
                return longLabel(barn);
            }
        }
        return code.isEmpty() ? text(barn, "barn_id") : code;
    }

    private static Result found(Map<String, Object> barn, List<Map<String, Object>> barns) {
        return new Result(Kind.FOUND, text(barn, "barn_id"), label(barn, barns), "");
    }

    private static Result single(List<Map<String, Object>> barns, String wanted, String column, boolean exact) {
        Map<String, Object> hit = null;
        for (Map<String, Object> b : barns) {
            String v = text(b, column);
            if (exact ? v.equals(wanted) : v.equalsIgnoreCase(wanted)) {
                if (hit != null) return null;
                hit = b;
            }
        }
        return hit == null ? null : found(hit, barns);
    }

    private static String longLabel(Map<String, Object> b) {
        String farm = text(b, "farm_name");
        return farm.isEmpty() ? text(b, "code") : text(b, "code") + " - " + farm;
    }

    private static String text(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
