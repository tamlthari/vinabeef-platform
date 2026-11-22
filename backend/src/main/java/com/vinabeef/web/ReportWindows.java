package com.vinabeef.web;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Time window of a report or a counter ("today", "last 7 days", or two dates the person typed).
 *
 * <p>People type local time, so "today" starts at local midnight (default Asia/Ho_Chi_Minh), not at UTC midnight:
 * a pass at 06:30 in Vietnam belongs to that day even though it is still the day before in UTC.
 */
final class ReportWindow {

    /** {@code fromInclusive <= t < toExclusive}. */
    record Window(Instant fromInclusive, Instant toExclusive, String label) {
    }

    private ReportWindow() {
    }

    static Window resolve(String preset, String from, String to, ZoneId zone, Instant now) {
        if (notBlank(from) || notBlank(to)) {
            return custom(from, to, zone, now);
        }
        return preset(preset, zone, now);
    }

    static Window preset(String preset, ZoneId zone, Instant now) {
        String p = preset == null ? "today" : preset.trim().toLowerCase(Locale.ROOT);
        Instant end = now.plusSeconds(1);
        return switch (p) {
            case "24h" -> new Window(now.minusSeconds(24 * 3600L), end, "Last 24 hours");
            case "7d" -> new Window(startOfDay(now, zone).minusSeconds(6 * 24 * 3600L), end, "Last 7 days");
            case "30d" -> new Window(startOfDay(now, zone).minusSeconds(29 * 24 * 3600L), end, "Last 30 days");
            case "all" -> new Window(Instant.EPOCH, end, "All time");
            default -> new Window(startOfDay(now, zone), end, "Today");
        };
    }

    /** Local midnight of the day that contains {@code now}. */
    static Instant startOfDay(Instant now, ZoneId zone) {
        return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }

    /**
     * Accepts {@code yyyy-MM-dd}, {@code yyyy-MM-ddTHH:mm} and {@code yyyy-MM-dd HH:mm}. A date without a time means the
     * whole day, so a "to" of 2026-11-21 includes everything up to the end of 21 November.
     */
    static Window custom(String from, String to, ZoneId zone, Instant now) {
        Instant start = notBlank(from) ? parse(from, zone, false) : Instant.EPOCH;
        Instant end = notBlank(to) ? parse(to, zone, true) : now.plusSeconds(1);
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("The end of the period must be after its start.");
        }
        return new Window(start, end, "Custom period");
    }

    private static Instant parse(String text, ZoneId zone, boolean endOfPeriod) {
        String s = text.trim().replace(' ', 'T');
        try {
            if (s.length() <= 10) {
                LocalDate day = LocalDate.parse(s);
                return (endOfPeriod ? day.plusDays(1) : day).atStartOfDay(zone).toInstant();
            }
            LocalDateTime moment = LocalDateTime.parse(s);
            // "to" is inclusive to the minute that was typed
            return (endOfPeriod ? moment.plusMinutes(1) : moment).atZone(zone).toInstant();
        } catch (DateTimeException e) {   // DateTimeParseException is a subclass
            throw new IllegalArgumentException("Cannot read the date '" + text + "'. Use yyyy-MM-dd or yyyy-MM-dd HH:mm.");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
