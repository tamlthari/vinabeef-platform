package com.vinabeef.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RfidReadEventRepository extends JpaRepository<RfidReadEvent, String> {
    boolean existsByEventKey(Long eventKey);
}