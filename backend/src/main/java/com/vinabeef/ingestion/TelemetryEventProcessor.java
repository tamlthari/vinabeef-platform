package com.vinabeef.ingestion;

/**
 * Port owned by the {@code ingestion} package.
 *
 * <p>Every ingress route (direct MQTT subscriber, Python adapter endpoint, edge batch replay,
 * REST gate simulator) hands a decoded {@link SafetrustEventDto} to whatever implements this
 * interface. {@code ingestion} only knows the contract; the {@code movement} package supplies the
 * implementation, so the dependency points one way: {@code movement -> ingestion}.
 */
public interface TelemetryEventProcessor {

    /**
     * Processes one reader event idempotently.
     *
     * @param dto          the decoded reader event
     * @param ingressMode  the route the event arrived on (for example {@code DIRECT_MQTT},
     *                     {@code PYTHON_ADAPTER}, {@code EDGE_BATCH} or {@code GATE_REST})
     * @param tIngressNano {@link System#nanoTime()} taken when the event entered the system, used
     *                     for latency measurement
     */
    void processSafetrustRead(SafetrustEventDto dto, String ingressMode, long tIngressNano);
}