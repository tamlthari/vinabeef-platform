package com.vinabeef.web;

/**
 * The recent reads of one gate's readers for the reader page, each with what became of it.
 *
 * <p>A read is not simply "a passage or an alert". The reads of a gate with an inner and an outer reader end in one of:
 * <ul>
 *   <li>{@code PASSAGE}: the read that completed a crossing (it is the movement's source event)</li>
 *   <li>{@code CROSSING_FIRST}: the earlier read of a crossing that completed; it is counted with the passage</li>
 *   <li>{@code HALF_CROSSING}: the tag is registered, but the other reader has not seen it (yet). It waits as an
 *       unpaired sighting. {@code half_side} says which reader saw it and {@code other_delta_ms} how far away the
 *       nearest sighting of the other reader is (positive: later), so a slow crossing is visible as such</li>
 *   <li>{@code NO_MOVEMENT}: the tag is registered but the read made no movement, for example a repeat read while the
 *       animal walks away from the reader, or an unpaired sighting that is older than the retention time</li>
 *   <li>{@code UNREGISTERED_TAG}: no animal is bound to the tag (an alert was raised)</li>
 *   <li>{@code COPY}: the second copy of a tap that came by both routes (Safetrust and the USB reader)</li>
 *   <li>{@code USB_UNMAPPED}: a USB read whose credential number matches no card</li>
 *   <li>{@code SYSTEM}: heartbeat, status or telemetry event of the reader</li>
 * </ul>
 *
 * <p>The animal is found the way the processing service finds it: the tag's assignment that was active when the read
 * was handled, else the event's user name when that is a live animal.
 *
 * <p>The newest {@value #LIMIT} reads are taken first and only those are joined, so the page stays cheap on a reader
 * with a long history. Parameters: the gate id, twice.
 */
final class ReaderReadFeed {

    static final int LIMIT = 50;

    static final String SQL = """
        WITH recent AS (
            SELECT re.event_id, re.event_key, re.reader_name, re.card_uuid, re.user_name, re.gate_action,
                   re.observed_at, COALESCE(re.received_at, re.observed_at) AS handled_at,
                   re.event_class, re.read_source, re.source_device, re.credential_identifier, re.duplicate_of
            FROM rfid_read_events re
            WHERE re.reader_name IN (SELECT g.reader_name FROM gates g WHERE g.gate_id = ?
                                     UNION SELECT gr.reader_name FROM gate_readers gr WHERE gr.gate_id = ?)
            ORDER BY re.observed_at DESC, re.event_id
            LIMIT 50
        )
        SELECT r.event_id, r.event_key, r.reader_name, r.card_uuid, r.user_name, r.gate_action, r.observed_at,
               r.event_class, r.read_source, r.source_device, r.credential_identifier, r.duplicate_of,
               m.movement_id,
               COALESCE(m.animal_id, fm.animal_id, ra.animal_id) AS animal_id,
               COALESCE(m.direction, fm.direction)               AS direction,
               CASE
                   WHEN r.duplicate_of IS NOT NULL          THEN 'COPY'
                   WHEN r.event_class = 'LOCAL_UNMAPPED'    THEN 'USB_UNMAPPED'
                   WHEN m.movement_id  IS NOT NULL          THEN 'PASSAGE'
                   WHEN fm.movement_id IS NOT NULL          THEN 'CROSSING_FIRST'
                   WHEN cand.event_id  IS NOT NULL          THEN 'HALF_CROSSING'
                   WHEN r.card_uuid IS NULL                 THEN 'SYSTEM'
                   WHEN ra.animal_id IS NULL                THEN 'UNREGISTERED_TAG'
                   ELSE 'NO_MOVEMENT'
               END AS outcome,
               cand.side AS half_side,
               (SELECT c2.seen_ms - cand.seen_ms
                  FROM gate_crossing_candidates c2
                 WHERE c2.gate_id = cand.gate_id AND c2.tag_epc = cand.tag_epc AND c2.side <> cand.side
                 ORDER BY ABS(c2.seen_ms - cand.seen_ms), c2.event_id
                 LIMIT 1) AS other_delta_ms
        FROM recent r
        LEFT JOIN movements m  ON m.source_event_id = r.event_id
        LEFT JOIN movements fm ON fm.first_event_id = r.event_id
        LEFT JOIN gate_crossing_candidates cand ON cand.event_id = r.event_id
        LEFT JOIN LATERAL (
            SELECT COALESCE(
                (SELECT ta.animal_id
                   FROM tag_assignments ta
                   JOIN rfid_tags t ON t.tag_id = ta.tag_id
                  WHERE t.epc_value = r.card_uuid
                    AND ta.valid_from <= r.handled_at
                    AND (ta.valid_to IS NULL OR ta.valid_to > r.handled_at)
                  ORDER BY ta.valid_from DESC
                  LIMIT 1),
                (SELECT a.animal_id FROM animals a
                  WHERE a.animal_id = r.user_name AND a.lifecycle_status <> 'ARCHIVED')
            ) AS animal_id
        ) ra ON TRUE
        ORDER BY r.observed_at DESC, r.event_id
        """;

    private ReaderReadFeed() { }
}
