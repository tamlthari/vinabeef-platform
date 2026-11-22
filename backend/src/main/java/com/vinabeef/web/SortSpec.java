package com.vinabeef.web;

import java.util.Locale;
import java.util.Map;

/**
 * Builds an ORDER BY clause from a sort key chosen on a page.
 *
 * <p>The key is looked up in a fixed table of SQL expressions; text from the request is never put into the SQL, so
 * there is nothing to inject. An unknown key or direction falls back to the default instead of failing, because a
 * stale bookmark should still open the page.
 */
final class SortSpec {

    private SortSpec() {
    }

    /**
     * @param allowed        sort key -> SQL expression (for example {@code "barn" -> "b.code"})
     * @param defaultKey     key used when the request has none or an unknown one
     * @param defaultDir     asc or desc, used with the default key
     * @param tieBreaker     SQL expression that makes the order total (for example {@code "a.animal_id"})
     * @return for example {@code "b.code DESC NULLS LAST, a.animal_id ASC"}
     */
    static String orderBy(String sort, String dir, Map<String, String> allowed,
                          String defaultKey, String defaultDir, String tieBreaker) {
        String key = sort != null && allowed.containsKey(sort.trim().toLowerCase(Locale.ROOT))
                ? sort.trim().toLowerCase(Locale.ROOT) : defaultKey;
        boolean usedDefaultKey = key.equals(defaultKey) && (sort == null || !allowed.containsKey(sort.trim().toLowerCase(Locale.ROOT)));
        String direction = normalise(dir);
        if (direction == null) {
            direction = usedDefaultKey ? normalise(defaultDir) : "ASC";
            if (direction == null) {
                direction = "ASC";
            }
        }
        String expression = allowed.get(key);
        String order = expression + " " + direction + " NULLS LAST";
        return expression.equals(tieBreaker) ? order : order + ", " + tieBreaker + " ASC";
    }

    /** The key that was really used, for the page to show the arrow on the right column. */
    static String effectiveKey(String sort, Map<String, String> allowed, String defaultKey) {
        return sort != null && allowed.containsKey(sort.trim().toLowerCase(Locale.ROOT))
                ? sort.trim().toLowerCase(Locale.ROOT) : defaultKey;
    }

    static String effectiveDir(String sort, String dir, Map<String, String> allowed, String defaultKey, String defaultDir) {
        String d = normalise(dir);
        if (d != null) {
            return d.toLowerCase(Locale.ROOT);
        }
        boolean usedDefaultKey = effectiveKey(sort, allowed, defaultKey).equals(defaultKey)
                && (sort == null || !allowed.containsKey(sort.trim().toLowerCase(Locale.ROOT)));
        String fallback = usedDefaultKey ? normalise(defaultDir) : "ASC";
        return (fallback == null ? "ASC" : fallback).toLowerCase(Locale.ROOT);
    }

    private static String normalise(String dir) {
        if (dir == null) {
            return null;
        }
        String d = dir.trim().toLowerCase(Locale.ROOT);
        return d.equals("asc") ? "ASC" : d.equals("desc") ? "DESC" : null;
    }
}
