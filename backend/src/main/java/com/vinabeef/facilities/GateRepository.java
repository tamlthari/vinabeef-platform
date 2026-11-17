package com.vinabeef.facilities;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface GateRepository extends JpaRepository<Gate, String> {
    Optional<Gate> findByReaderName(String readerName);
}