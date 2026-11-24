package com.vinabeef.movement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "movements")
@Getter
@Setter
public class Movement {
    @Id
    @Column(name = "movement_id")
    private String movementId;

    @Column(name = "animal_id", nullable = false)
    private String animalId;

    @Column(name = "gate_id", nullable = false)
    private String gateId;

    @Column(name = "source_event_id", nullable = false)
    private String sourceEventId;

    @Column(nullable = false)
    private String direction;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** The movement is a complete crossing (every movement of a gate without a reader pair is). */
    public static final String COMPLETE = "COMPLETE";
    /** The animal passed one reader of a pair and has not (yet) passed the other. */
    public static final String HALF_CROSSING = "HALF_CROSSING";

    /** PAIRED_READERS (inner/outer order), GATE_MODE (one reader, presence toggle) or ONE_WAY_GATE (IN_ONLY / OUT_ONLY gate). */
    @Column(name = "detection_method", nullable = false)
    private String detectionMethod = "GATE_MODE";

    /** For PAIRED_READERS: the first of the two reads. {@code sourceEventId} is the second one. */
    @Column(name = "first_event_id")
    private String firstEventId;

    @Column(name = "crossing_started_at")
    private Instant crossingStartedAt;

    /**
     * {@link #COMPLETE} or {@link #HALF_CROSSING}. A half crossing has a direction (the animal's last presence turned
     * around, like at a single-reader gate) and becomes complete when the other reader of the pair sees the animal;
     * the direction is then decided by the order of the two reads.
     */
    @Column(name = "crossing_status", nullable = false)
    private String crossingStatus = COMPLETE;

    /**
     * For a half crossing: the reader that saw the animal, INNER or OUTER. Null once the crossing is complete, and null
     * for a half crossing that can no longer be completed because the readers of the gate were changed.
     */
    @Column(name = "reader_side")
    private String readerSide;

    /** The animal's presence before this movement, so a half crossing can be corrected if the other reader disagrees. */
    @Column(name = "previous_presence")
    private String previousPresence;

    @Column(name = "previous_barn_id")
    private String previousBarnId;
}
