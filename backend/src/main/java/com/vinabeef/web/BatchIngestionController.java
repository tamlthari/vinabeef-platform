package com.vinabeef.web;

import com.vinabeef.audit.Audited;
import com.vinabeef.ingestion.SafetrustEventDto;
// import com.vinabeef.movement.SafetrustProcessingService;
import com.vinabeef.ingestion.EdgeBatchReplay;
import com.vinabeef.ingestion.DeadLetterService;
import com.vinabeef.ingestion.SafetrustEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/ingestion")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class BatchIngestionController {

    private final EdgeBatchReplay edgeBatchReplay;
    private final DeadLetterService deadLetterService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Reachability probe for Barn Edge Gateways. ONLINE means this backend can really write: the probe touches
     * the database, so an edge gateway does not replay its backlog into a backend whose database is down.
     */
    @GetMapping("/health")
    public ResponseEntity<?> edgeHealthCheck() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception ex) {
            log.warn("[EDGE_HEALTH] Database not reachable: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DEGRADED",
                    "reason", "database unavailable",
                    "serverTime", Instant.now().toString()
            ));
        }
        return ResponseEntity.ok(Map.of(
                "status", "ONLINE",
                "serverTime", Instant.now().toString()
        ));
    }

    /** Replays events that the MQTT subscriber could not process (they are kept in ingestion_dead_letters). */
    @PostMapping("/dead-letters/replay")
    public ResponseEntity<?> replayDeadLetters(@RequestParam(defaultValue = "500") int limit) {
        Map<String, Integer> result = deadLetterService.replayOpen(Math.max(1, Math.min(limit, 5000)));
        return ResponseEntity.ok(Map.of(
                "replayed", result.get("replayed"),
                "failed", result.get("failed"),
                "stillOpen", deadLetterService.countOpen()
        ));
    }

    /**
     * Idempotent chronological replay of edge-buffered events.
     *
     * <p>The response lists the outcome of every event. {@code OK} (including a duplicate that was already
     * stored) means the edge may delete it; {@code FAILED} means it must keep it and retry. When any event
     * failed the status is 207, so an older gateway that only deletes its queue on 200 keeps the whole batch
     * and retries it, which is safe because every event is idempotent on EVENT_KEY.
     */
    @PostMapping("/batch")
    @Audited(action = "INGEST_EDGE_BATCH", targetType = "RFID_BATCH")
    public ResponseEntity<?> ingestEdgeBatch(@RequestBody List<SafetrustEventDto> events) {
        if (events == null || events.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "BATCH_COMPLETED", "processed", 0, "failed", 0, "results", List.of()));
        }

        EdgeBatchReplay.Outcome outcome = edgeBatchReplay.replay(events, "EDGE_BATCH");

        List<Map<String, Object>> results = new ArrayList<>();
        for (EdgeBatchReplay.EventResult r : outcome.results()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("eventKey", r.eventKey());
            result.put("status", r.ok() ? "OK" : "FAILED");
            if (!r.ok()) {
                result.put("error", r.error());
            }
            results.add(result);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", outcome.allOk() ? "BATCH_COMPLETED" : "BATCH_PARTIAL");
        body.put("processed", outcome.processed());
        body.put("failed", outcome.failed());
        body.put("elapsedMs", outcome.elapsedMs());
        body.put("results", results);
        return ResponseEntity.status(outcome.allOk() ? HttpStatus.OK : HttpStatus.MULTI_STATUS).body(body);
    }
}
