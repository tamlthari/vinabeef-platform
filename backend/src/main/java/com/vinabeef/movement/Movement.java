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
}