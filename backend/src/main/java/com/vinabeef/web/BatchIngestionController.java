package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.movement.SafetrustProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/ingestion")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class BatchIngestionController {

    private final SafetrustProcessingService processingService;

    /**
     * Lightweight WAN reachability probe for Barn Edge Gateways
     */
    @GetMapping("/health")
    public ResponseEntity<?> edgeHealthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "ONLINE",
                "serverTime", Instant.now().toString()
        ));
    }

    /**
     * Enhancement 2: Idempotent Chronological Replay of Edge-Buffered Gate Events
     */
    @PostMapping("/batch")
    @Audited(action = "INGEST_EDGE_BATCH", targetType = "RFID_BATCH")
    public ResponseEntity<?> ingestEdgeBatch(@RequestBody List<SafetrustEventDto> events) {
        if (events == null || events.isEmpty()) {
            return ResponseEntity.ok(Map.of("processed", 0, "failed", 0));
        }

        // Sort strictly chronologically by hardware timestamp so IN/OUT transitions stay ordered
        List<SafetrustEventDto> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparingLong(e -> e.getTimestamp() != null ? e.getTimestamp() : 0L));

        int processed = 0;
        int failed = 0;
        long tStart = System.nanoTime();

        for (SafetrustEventDto dto : ordered) {
            try {
                processingService.processSafetrustRead(dto, "EDGE_BATCH", System.nanoTime());
                processed++;
            } catch (Exception ex) {
                failed++;
                log.error("[EDGE_BATCH] Failed replaying eventKey={}: {}", dto.getEventKey(), ex.getMessage());
            }
        }

        double totalMs = Math.round((System.nanoTime() - tStart) / 10000.0) / 100.0;
        log.info("[EDGE_BATCH] Replayed {} events ({} failed) in {} ms", processed, failed, totalMs);

        return ResponseEntity.ok(Map.of(
                "status", "BATCH_COMPLETED",
                "processed", processed,
                "failed", failed,
                "elapsedMs", totalMs
        ));
    }
}