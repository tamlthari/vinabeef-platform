package com.vinabeef.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the text of a filter on a list page into values that are safe to put in a statement.
 *
 * <p>A filter either has to be one of a fixed set of words (health status, presence, ...) or is an id that goes in as a
 * bind parameter. Nothing from the request is ever put into the SQL text. A value that is not in the fixed set is
 * dropped, not rejected, so an old bookmark still opens the page.
 */
final class ListFilters {

    private ListFilters() {
    }

    /** The one allowed word that {@code value} spells (ignoring case and spaces), or null for blank / unknown. */
    static String oneOf(String value, String... allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        for (String a : allowed) {
            if (a.toUpperCase(Locale.ROOT).equals(v)) {
                return a;
            }
        }
        return null;
    }

    /** The allowed words found in a comma-separated list, each once, in the order the list had them. */
    static List<String> manyOf(String csv, String... allowed) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : split(csv)) {
            String hit = oneOf(part, allowed);
            if (hit != null) {
                out.add(hit);
            }
        }
        return List.copyOf(out);
    }

    /** The non-blank, trimmed values of a comma-separated list, each once (barn ids and the like: bound, never inlined). */
    static List<String> ids(String csv) {
        Set<String> out = new LinkedHashSet<>(split(csv));
        return List.copyOf(out);
    }

    /** Most ids one filter may carry; a longer list is cut so a request cannot build an enormous IN (...). */
    static final int MAX_IDS = 50;

    private static List<String> split(String csv) {
        List<String> parts = new ArrayList<>();
        if (csv == null) {
            return parts;
        }
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty() && parts.size() < MAX_IDS) {
                parts.add(t);
            }
        }
        return parts;
    }

    /** {@code ?, ?, ?} for {@code n} values. */
    static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
