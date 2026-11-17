package com.vinabeef.web;

import com.vinabeef.ingestion.IngestionHealthService;
import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.movement.SafetrustProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ingestion")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class SafetrustIngressController {

    private final SafetrustProcessingService processingService;
    private final IngestionHealthService healthService;

    @GetMapping("/failover-status")
    public ResponseEntity<Map<String, Object>> getFailoverStatus() {
        return ResponseEntity.ok(healthService.getHealthStatus());
    }

    @PostMapping("/safetrust-event")
    public ResponseEntity<String> ingestSafetrustEvent(@RequestBody SafetrustEventDto eventDto) {
        long tIngressNano = System.nanoTime();
        boolean failoverActive = healthService.isFailoverRequired();

        if (!failoverActive) {
            log.debug("[PYTHON_ADAPTER] Received event {}, but Direct MQTT is healthy. Processing idempotently.", eventDto.getEventKey());
        } else {
            log.warn("[FAILOVER_ENGAGED] Ingested event {} via Python Adapter (Direct MQTT Degraded).", eventDto.getEventKey());
        }

        processingService.processSafetrustRead(eventDto, "PYTHON_ADAPTER", tIngressNano);
        return ResponseEntity.ok("ACCEPTED");
    }
}