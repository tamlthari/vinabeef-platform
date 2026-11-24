package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text-level checks of the reader page's read feed. The statement was also run against a PostgreSQL 16 database built
 * from the real migrations, with the cases in the class comment of {@link ReaderReadFeed}.
 */
class ReaderReadFeedTest {

    private static final String SQL = ReaderReadFeed.SQL;

    @Test
    void theGateIdIsBoundTwice() {
        long placeholders = SQL.chars().filter(c -> c == '?').count();
        assertEquals(2L, placeholders, "gates.reader_name and gate_readers.gate_id each take the gate id");
    }

    @Test
    void theFeedStaysAtTheNewestFiftyReads() {
        assertEquals(50, ReaderReadFeed.LIMIT);
        assertTrue(SQL.contains("LIMIT " + ReaderReadFeed.LIMIT), "the limit in the statement must match the constant");
        // the limit is applied before the joins, so a reader with a long history stays cheap
        assertTrue(SQL.indexOf("LIMIT 50") < SQL.indexOf("FROM recent r"));
    }

    @Test
    void theFirstReadOfACompletedCrossingIsFoundThroughFirstEventId() {
        // movements.source_event_id is only the LATER read of a pair; the earlier one is movements.first_event_id.
        // Joining only on source_event_id made that read look like a tag that is not registered.
        assertTrue(SQL.contains("LEFT JOIN movements m  ON m.source_event_id = r.event_id"));
        assertTrue(SQL.contains("LEFT JOIN movements fm ON fm.first_event_id = r.event_id"));
    }

    @Test
    void anUnpairedReadIsAHalfCrossingNotAnUnregisteredTag() {
        assertTrue(SQL.contains("LEFT JOIN gate_crossing_candidates cand ON cand.event_id = r.event_id"));
        assertTrue(SQL.indexOf("THEN 'HALF_CROSSING'") < SQL.indexOf("THEN 'UNREGISTERED_TAG'"),
                "a read waiting for its partner must be classified before the registered-tag test");
    }

    @Test
    void everyOutcomeThePageKnowsIsProduced() {
        List<String> outcomes = List.of("COPY", "USB_UNMAPPED", "PASSAGE", "CROSSING_FIRST", "HALF_CROSSING",
                "SYSTEM", "UNREGISTERED_TAG", "NO_MOVEMENT");
        for (String outcome : outcomes) {
            assertTrue(SQL.contains("'" + outcome + "'"), outcome);
        }
    }

    @Test
    void theAnimalIsResolvedTheWayTheProcessingServiceDoes() {
        // the tag's assignment that was active when the read was handled, else the event's user name if a live animal
        assertTrue(SQL.contains("ta.valid_from <= r.handled_at"));
        assertTrue(SQL.contains("ta.valid_to IS NULL OR ta.valid_to > r.handled_at"));
        assertTrue(SQL.contains("a.lifecycle_status <> 'ARCHIVED'"));
    }

    @Test
    void theColumnsTheTemplateRendersAreAllReturned() {
        for (String column : List.of("event_class", "read_source", "source_device", "credential_identifier",
                "duplicate_of", "movement_id", "animal_id", "direction", "outcome", "half_side", "other_delta_ms")) {
            assertTrue(SQL.contains(column), column);
        }
    }
}
