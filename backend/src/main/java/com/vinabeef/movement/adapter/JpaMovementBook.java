package com.vinabeef.movement.adapter;

import com.vinabeef.movement.Movement;
import com.vinabeef.movement.MovementRepository;
import com.vinabeef.movement.PresenceState;
import com.vinabeef.movement.PresenceStateRepository;
import com.vinabeef.movement.port.MovementBook;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@link MovementBook} over the movement and presence repositories. */
@Component
@RequiredArgsConstructor
public class JpaMovementBook implements MovementBook {

    private final MovementRepository movements;
    private final PresenceStateRepository presence;

    @Override
    public Optional<PresenceState> presenceOf(String animalId) {
        return presence.findById(animalId);
    }

    @Override
    public void saveMovement(Movement movement) {
        movements.save(movement);
    }

    @Override
    public Optional<Movement> findMovement(String movementId) {
        return movements.findById(movementId);
    }

    @Override
    public void savePresence(PresenceState state) {
        presence.save(state);
    }
}
