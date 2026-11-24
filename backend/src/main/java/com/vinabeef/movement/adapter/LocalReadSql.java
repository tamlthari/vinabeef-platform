package com.vinabeef.movement.adapter;

import java.util.List;

/**
 * The statements behind reads from a USB reader on the barn gateway (route {@code LOCAL_USB}) and the matching of a read
 * that arrives by both routes. They are built here, with their parameters in one place, so the SQL can be run against a real
 * PostgreSQL exactly as the services run it.
 */
final class LocalReadSql {

    record Query(String sql, List<Object> params) {
    }

    /** How many of a credential's latest Safetrust reads are looked at when finding its card. */
    static final int HISTORY_READS = 50;

    private LocalReadSql() {
    }

    /**
     * The card(s) Safetrust itself reported for this credential number, looking at its latest reads. Exactly one card means
     * the number is known; two means the number was moved to another card and nothing is guessed.
     */
    static Query cardByHistory(String credential) {
        return new Query("""
                SELECT DISTINCT r.card_uuid
                FROM (SELECT card_uuid
                      FROM rfid_read_events
                      WHERE credential_identifier = ? AND card_uuid IS NOT NULL
                        AND read_source = 'SAFETRUST' AND event_class = 'CARD_READ'
                      ORDER BY hardware_timestamp DESC
                      LIMIT ?) r
                """, List.of(credential, HISTORY_READS));
    }

    /** The active tag(s) registered with this credential number (rfid_tags.credential_identifier). */
    static Query cardByRegister(String credential) {
        return new Query("""
                SELECT epc_value FROM rfid_tags
                WHERE credential_identifier = ? AND status = 'ACTIVE'
                LIMIT 2
                """, List.of(credential));
    }

    /** Serialises the reads of one tag at one reader, so two routes' copies cannot both become the first copy. */
    static Query lock(String card, String readerName) {
        return new Query("SELECT pg_advisory_xact_lock(hashtext(?))", List.of("xsrc|" + card + "|" + readerName));
    }

    /**
     * The nearest read of the OTHER route for the same tag at the same reader inside the window that is not yet paired with a
     * copy. A read is paired with at most one copy, so two real taps stay two taps.
     */
    static Query findTwin(String eventId, String readerName, String card, String readSource, long hardwareMs, long windowMs) {
        return new Query("""
                SELECT event_id FROM rfid_read_events
                WHERE reader_name = ? AND card_uuid = ? AND event_class = 'CARD_READ'
                  AND duplicate_of IS NULL AND twin_event_id IS NULL
                  AND read_source <> ? AND event_id <> ?
                  AND hardware_timestamp BETWEEN ? AND ?
                ORDER BY ABS(hardware_timestamp - ?), hardware_timestamp, event_id
                LIMIT 1
                FOR UPDATE
                """, List.of(readerName, card, readSource, eventId, hardwareMs - windowMs, hardwareMs + windowMs, hardwareMs));
    }

    /** The first copy learns its second copy. Updates nothing if it was paired in the meantime. */
    static Query markPrimary(String primaryId, String copyId) {
        return new Query("""
                UPDATE rfid_read_events SET twin_event_id = ?
                WHERE event_id = ? AND twin_event_id IS NULL AND duplicate_of IS NULL
                """, List.of(copyId, primaryId));
    }

    /** The second copy points to the first. */
    static Query markDuplicate(String copyId, String primaryId) {
        return new Query("UPDATE rfid_read_events SET duplicate_of = ? WHERE event_id = ?", List.of(primaryId, copyId));
    }

    /** Local reads whose credential number mapped to no card when they arrived, oldest first. */
    static Query unmappedIds(int limit) {
        return new Query("""
                SELECT event_id FROM rfid_read_events
                WHERE event_class = 'LOCAL_UNMAPPED'
                ORDER BY hardware_timestamp, event_id
                LIMIT ?
                """, List.of(limit));
    }

    /** One unmapped local read, locked so two callers do not process it twice. */
    static Query unmappedRow(String eventId) {
        return new Query("""
                SELECT event_id, event_key, reader_name, credential_identifier, hardware_timestamp, source_device, time_source
                FROM rfid_read_events
                WHERE event_id = ? AND event_class = 'LOCAL_UNMAPPED'
                FOR UPDATE
                """, List.of(eventId));
    }

    /** The card was found: the read becomes an ordinary card read. */
    static Query resolveRow(String eventId, String card, String resolvedBy) {
        return new Query("""
                UPDATE rfid_read_events
                SET card_uuid = ?, card_resolved_by = ?, event_class = 'CARD_READ'
                WHERE event_id = ? AND event_class = 'LOCAL_UNMAPPED'
                """, List.of(card, resolvedBy, eventId));
    }
}
