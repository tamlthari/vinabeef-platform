package com.vinabeef.web;

// import com.vinabeef.movement.IngestionBenchmarkService;
import com.vinabeef.ingestion.IngestionBenchmarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/benchmark")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BenchmarkController {

    private final IngestionBenchmarkService benchmarkService;

    @Value("${vinabeef.ingestion.mode}")
    private String activeMode;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "activeModeInConfig", activeMode,
                "pythonAdapterMetrics", benchmarkService.getReport("PYTHON_ADAPTER"),
                "directMqttMetrics", benchmarkService.getReport("DIRECT_MQTT")
        ));
    }

    @DeleteMapping("/reset")
    public ResponseEntity<String> resetStats() {
        benchmarkService.clear("PYTHON_ADAPTER");
        benchmarkService.clear("DIRECT_MQTT");
        return ResponseEntity.ok("BENCHMARK_STATS_CLEARED");
    }
}