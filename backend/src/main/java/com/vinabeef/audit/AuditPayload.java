package com.vinabeef.audit;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns the arguments of an audited method into something safe to store in {@code audit_entries.details}.
 *
 * <p>The aspect used to store the raw argument array. That put plain-text passwords into the audit table
 * (the create-user request carries one), tried to serialise {@code Authentication} and servlet objects, and could
 * grow without limit. This class keeps what an auditor needs (which record was touched, which values were sent)
 * and drops or masks the rest. It has no Spring or Jackson dependency: the caller hands in the bean-to-map
 * conversion, so it is unit-tested on its own.
 */
final class AuditPayload {

    static final String MASK = "***";
    static final int MAX_TEXT = 200;
    static final int MAX_ITEMS = 20;
    static final int MAX_DEPTH = 4;

    /** Field names whose value must never be stored. */
    private static final Pattern SECRET_NAME =
            Pattern.compile("(?i).*(pass(word|wd|phrase)?|secret|token|authorization|api[-_]?key).*");

    private AuditPayload() {
    }

    static List<Object> sanitize(Object[] args, Function<Object, Map<String, Object>> beanToMap) {
        List<Object> out = new ArrayList<>();
        if (args == null) {
            return out;
        }
        for (Object arg : args) {
            if (arg == null || isInfrastructure(arg.getClass())) {
                continue;
            }
            out.add(clean(arg, beanToMap, 0));
        }
        return out;
    }

    static boolean isSecretName(String name) {
        return name != null && SECRET_NAME.matcher(name).matches();
    }

    /** Principals, servlet request/response objects and uploads say nothing about the business action. */
    static boolean isInfrastructure(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (startsWithInfra(c.getName())) {
                return true;
            }
            for (Class<?> i : c.getInterfaces()) {
                if (Principal.class.isAssignableFrom(i) || startsWithInfra(i.getName()) || isInfrastructure(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean startsWithInfra(String className) {
        return className.startsWith("jakarta.servlet.")
                || className.startsWith("javax.servlet.")
                || className.startsWith("org.springframework.security.")
                || className.startsWith("org.springframework.web.multipart.")
                || className.startsWith("org.springframework.http.HttpHeaders");
    }

    @SuppressWarnings("unchecked")
    private static Object clean(Object value, Function<Object, Map<String, Object>> beanToMap, int depth) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence text) {
            String s = text.toString();
            return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "...(" + s.length() + " chars)" : s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (depth >= MAX_DEPTH) {
            return "...";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                out.put(key, isSecretName(key) ? MASK : clean(entry.getValue(), beanToMap, depth + 1));
            }
            return out;
        }
        if (value instanceof Collection<?> items) {
            return cleanItems(items, beanToMap, depth);
        }
        if (value.getClass().isArray() && value instanceof Object[] array) {
            return cleanItems(java.util.Arrays.asList(array), beanToMap, depth);
        }
        try {
            Map<String, Object> asMap = beanToMap.apply(value);
            return asMap == null ? value.getClass().getSimpleName() : clean(asMap, beanToMap, depth);
        } catch (RuntimeException notABean) {
            return value.getClass().getSimpleName();
        }
    }

    private static List<Object> cleanItems(Collection<?> items, Function<Object, Map<String, Object>> beanToMap, int depth) {
        List<Object> out = new ArrayList<>();
        int n = 0;
        for (Object item : items) {
            if (n++ == MAX_ITEMS) {
                out.add("...(" + items.size() + " items)");
                break;
            }
            out.add(clean(item, beanToMap, depth + 1));
        }
        return out;
    }
}
