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

    /** PAIRED_READERS (inner/outer order), GATE_MODE (one reader, presence toggle) or ONE_WAY_GATE (IN_ONLY / OUT_ONLY gate). */
    @Column(name = "detection_method", nullable = false)
    private String detectionMethod = "GATE_MODE";

    /** For PAIRED_READERS: the first of the two reads. {@code sourceEventId} is the second one. */
    @Column(name = "first_event_id")
    private String firstEventId;

    @Column(name = "crossing_started_at")
    private Instant crossingStartedAt;
}