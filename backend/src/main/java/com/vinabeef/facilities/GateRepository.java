package com.vinabeef.facilities;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface GateRepository extends JpaRepository<Gate, String> {
    /** Retired gates keep their reader name for history, so only the active gate counts. */
    Optional<Gate> findByReaderNameAndRetiredAtIsNull(String readerName);
}