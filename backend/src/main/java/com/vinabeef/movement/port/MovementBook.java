package com.vinabeef.movement.port;

import com.vinabeef.movement.Movement;
import com.vinabeef.movement.PresenceState;

import java.util.Optional;

/** Output port: the movement history and each animal's current presence. */
public interface MovementBook {

    Optional<PresenceState> presenceOf(String animalId);

    void saveMovement(Movement movement);

    void savePresence(PresenceState state);
}
