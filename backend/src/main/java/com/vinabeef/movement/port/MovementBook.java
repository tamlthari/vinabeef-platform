package com.vinabeef.movement.port;

import com.vinabeef.movement.Movement;
import com.vinabeef.movement.PresenceState;

import java.util.Optional;

/** Output port: the movement history and each animal's current presence. */
public interface MovementBook {

    Optional<PresenceState> presenceOf(String animalId);

    /** Stores a new movement, or the changes to one that is already stored (a half crossing that was completed). */
    void saveMovement(Movement movement);

    Optional<Movement> findMovement(String movementId);

    void savePresence(PresenceState state);
}
