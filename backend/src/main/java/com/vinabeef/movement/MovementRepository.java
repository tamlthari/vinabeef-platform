package com.vinabeef.movement;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MovementRepository extends JpaRepository<Movement, String> {
    List<Movement> findTop15ByOrderByOccurredAtDesc();
}