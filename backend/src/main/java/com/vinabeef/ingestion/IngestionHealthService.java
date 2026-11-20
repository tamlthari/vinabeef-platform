package com.vinabeef.ingestion;

// import com.vinabeef.movement.IngestionBenchmarkService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionHealthService {

    private final IngestionBenchmarkService benchmarkService;

    public static final double LATENCY_THRESHOLD_MS = 500.0;
    private static final long HEARTBEAT_TIMEOUT_MS = 60_000; // 60 seconds without events

    @Getter
    @Setter
    private volatile boolean directMqttConnected = false;

    @Getter
    @Setter
    private volatile String disconnectReason = "Initializing";

    @Getter
    private volatile long lastDirectMessageTimestamp = System.currentTimeMillis();

    public void recordDirectHeartbeat() {
        this.lastDirectMessageTimestamp = System.currentTimeMillis();
    }

    public boolean isLatencyBreached() {
        return benchmarkService.isRecentLatencyBreached("DIRECT_MQTT", LATENCY_THRESHOLD_MS);
    }

    public boolean isFailoverRequired() {
        if (!directMqttConnected) {
            return true;
        }
        return isLatencyBreached();
    }

    public Map<String, Object> getHealthStatus() {
        boolean latencyBreached = isLatencyBreached();
        boolean failover = isFailoverRequired();
        double recentAvg = benchmarkService.getRecentAverageLatency("DIRECT_MQTT");

        return Map.of(
                "directMqttConnected", directMqttConnected,
                "disconnectReason", directMqttConnected ? "NONE" : disconnectReason,
                "recentAvgLatencyMs", Math.round(recentAvg * 100.0) / 100.0,
                "latencyThresholdMs", LATENCY_THRESHOLD_MS,
                "latencyBreached", latencyBreached,
                "failoverRequired", failover,
                "activeIngressRoute", failover ? "PYTHON_ADAPTER" : "DIRECT_MQTT",
                "lastDirectEventAgeSeconds", (System.currentTimeMillis() - lastDirectMessageTimestamp) / 1000
        );
    }
}