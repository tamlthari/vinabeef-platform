package com.vinabeef.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;

/**
 * "Today" for the farm, in the farm's own time zone ({@code vinabeef.timezone}, default Asia/Ho_Chi_Minh).
 * The database stores UTC instants; days on screen and in counters are local days.
 */
@Component
public class ReportClock {

    private final ZoneId zone;

    public ReportClock(@Value("${vinabeef.timezone:Asia/Ho_Chi_Minh}") String zoneId) {
        this.zone = ZoneId.of(zoneId);
    }

    public ZoneId zone() {
        return zone;
    }

    public Instant now() {
        return Instant.now();
    }

    /** Local midnight to now. */
    ReportWindow.Window today() {
        return ReportWindow.preset("today", zone, now());
    }
}
