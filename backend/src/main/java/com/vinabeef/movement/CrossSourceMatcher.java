package com.vinabeef.movement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * One physical tap can reach the system by two routes: the reader's own event through the Safetrust cloud, and the same
 * tag typed by a USB reader on the barn gateway. They have different EVENT_KEYs, so EVENT_KEY cannot tell they are one tap.
 * This recognises them: the same tag at the same reader (by name) within {@code window-ms} of each other, one copy from each
 * route. The first copy to be processed counts; the second is kept as evidence, marked {@code duplicate_of}, and makes no
 * movement. Each read is paired with at most one copy.
 *
 * <p>It also covers a Safetrust read that arrives hours later because the reader had buffered it while offline.
 * Reads of the same route are never merged here (repeat reads of one reader are the gate's debounce).
 */
@Component
@Slf4j
public class CrossSourceMatcher {

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final long windowMs;

    public CrossSourceMatcher(JdbcTemplate jdbc,
                              @Value("${ingestion.cross-source.enabled:true}") boolean enabled,
                              @Value("${ingestion.cross-source.window-ms:10000}") long windowMs) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.windowMs = Math.max(0, windowMs);
    }

    public boolean enabled() {
        return enabled;
    }

    public long windowMs() {
        return windowMs;
    }

    /**
     * Call once per card read, before it is turned into a movement. Returns the event id of the first copy when this read is
     * the second copy of a tap that was already counted (the caller must then stop), otherwise empty.
     */
    public Optional<String> claimFirstCopy(String eventId, String readerName, String card, String readSource, long hardwareMs) {
        if (!enabled || card == null || readerName == null) {
            return Optional.empty();
        }
        LocalReadSql.Query lock = LocalReadSql.lock(card, readerName);
        jdbc.query(lock.sql(), rs -> {
        }, lock.params().toArray());

        LocalReadSql.Query find = LocalReadSql.findTwin(eventId, readerName, card, readSource, hardwareMs, windowMs);
        List<String> found = jdbc.queryForList(find.sql(), String.class, find.params().toArray());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        String first = found.get(0);

        LocalReadSql.Query primary = LocalReadSql.markPrimary(first, eventId);
        if (jdbc.update(primary.sql(), primary.params().toArray()) != 1) {
            return Optional.empty();           // it was paired with another copy a moment ago
        }
        LocalReadSql.Query copy = LocalReadSql.markDuplicate(eventId, first);
        jdbc.update(copy.sql(), copy.params().toArray());
        log.info("Read {} of tag '{}' at reader '{}' ({}) is the second copy of read {}: no second movement.",
                eventId, card, readerName, readSource, first);
        return Optional.of(first);
    }
}
