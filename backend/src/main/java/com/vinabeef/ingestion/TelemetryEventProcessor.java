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

    /**
     * Retries one stored read from a barn gateway's USB reader whose credential number mapped to no card when it arrived.
     *
     * @param eventId the stored read
     * @return true when its card is known now and the read was processed; false when it is still unmapped
     */
    boolean reprocessUnmappedLocalRead(String eventId);

    /** The oldest stored USB reads that still map to no card (at most {@code limit}). */
    java.util.List<String> unmappedLocalReadIds(int limit);

    /** How many stored USB reads still map to no card. */
    int countUnmappedLocalReads();
}
