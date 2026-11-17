package com.vinabeef.movement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "presence_states")
@Getter
@Setter
public class PresenceState {
    @Id
    @Column(name = "animal_id")
    private String animalId;

    @Column(name = "current_barn_id", nullable = false)
    private String currentBarnId;

    @Column(name = "presence_status", nullable = false)
    private String presenceStatus;

    @Column(name = "last_movement_id")
    private String lastMovementId;

    @Column(name = "updated_at")
    private Instant updatedAt;
}