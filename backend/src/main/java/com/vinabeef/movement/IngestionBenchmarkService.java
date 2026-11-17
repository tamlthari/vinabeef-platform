package com.vinabeef.movement;

import org.springframework.stereotype.Service;

import java.util.DoubleSummaryStatistics;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class IngestionBenchmarkService {

    public record MetricSnapshot(
            String mode,
            long eventKey,
            double ingressToDedupMs,
            double dedupToDbMs,
            double totalInternalMs,
            double edgeToStoreMs
    ) {}

    private final Map<String, Queue<MetricSnapshot>> metricsByMode = new ConcurrentHashMap<>();

    public void recordMetric(String mode, long eventKey, long tIngress, long tDedupDone, long tDbDone, long tSensorHardware) {
        metricsByMode.putIfAbsent(mode, new ConcurrentLinkedQueue<>());
        Queue<MetricSnapshot> queue = metricsByMode.get(mode);

        double ingressToDedup = (tDedupDone - tIngress) / 1_000_000.0;
        double dedupToDb = (tDbDone - tDedupDone) / 1_000_000.0;
        double totalInternal = (tDbDone - tIngress) / 1_000_000.0;
        double edgeToStore = (System.currentTimeMillis() - tSensorHardware);

        queue.offer(new MetricSnapshot(mode, eventKey, ingressToDedup, dedupToDb, totalInternal, edgeToStore));
        if (queue.size() > 500) {
            queue.poll();
        }
    }

    public double getRecentAverageLatency(String mode) {
        Queue<MetricSnapshot> queue = metricsByMode.get(mode);
        if (queue == null || queue.isEmpty()) {
            return 0.0;
        }
        return queue.stream()
                .mapToDouble(MetricSnapshot::totalInternalMs)
                .average()
                .orElse(0.0);
    }

    public boolean isRecentLatencyBreached(String mode, double thresholdMs) {
        Queue<MetricSnapshot> queue = metricsByMode.get(mode);
        if (queue == null || queue.size() < 3) {
            return false; // Wait for minimum 3 samples to avoid false alarms on first cold read
        }
        // Take the average of the last 5 events
        double recentAvg = queue.stream()
                .mapToDouble(MetricSnapshot::totalInternalMs)
                .skip(Math.max(0, queue.size() - 5))
                .average()
                .orElse(0.0);

        return recentAvg > thresholdMs;
    }

    public Map<String, Object> getReport(String mode) {
        Queue<MetricSnapshot> samples = metricsByMode.getOrDefault(mode, new ConcurrentLinkedQueue<>());
        if (samples.isEmpty()) {
            return Map.of("mode", mode, "sampleCount", 0, "message", "No events captured yet under this mode");
        }

        DoubleSummaryStatistics internalStats = samples.stream()
                .mapToDouble(MetricSnapshot::totalInternalMs)
                .summaryStatistics();

        DoubleSummaryStatistics edgeStats = samples.stream()
                .mapToDouble(MetricSnapshot::edgeToStoreMs)
                .summaryStatistics();

        double p95Internal = samples.stream()
                .mapToDouble(MetricSnapshot::totalInternalMs)
                .sorted().skip((long) (samples.size() * 0.95))
                .findFirst().orElse(internalStats.getMax());

        return Map.of(
                "mode", mode,
                "sampleCount", samples.size(),
                "internalLatencyMs", Map.of(
                        "avg", Math.round(internalStats.getAverage() * 100.0) / 100.0,
                        "min", Math.round(internalStats.getMin() * 100.0) / 100.0,
                        "max", Math.round(internalStats.getMax() * 100.0) / 100.0,
                        "p95", Math.round(p95Internal * 100.0) / 100.0
                ),
                "edgeToDatabaseMs", Map.of(
                        "avg", Math.round(edgeStats.getAverage() * 100.0) / 100.0,
                        "max", Math.round(edgeStats.getMax() * 100.0) / 100.0
                )
        );
    }

    public void clear(String mode) {
        metricsByMode.remove(mode);
    }
}