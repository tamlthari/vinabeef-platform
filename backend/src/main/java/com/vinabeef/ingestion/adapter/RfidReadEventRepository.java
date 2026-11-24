package com.vinabeef.ingestion.adapter;

import com.vinabeef.ingestion.RfidReadEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RfidReadEventRepository extends JpaRepository<RfidReadEvent, String> {
    boolean existsByEventKey(Long eventKey);
}