package com.vinabeef.ingestion;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A reference implementation of the port for tests: it stores event keys in memory. */
class InMemoryTelemetryProcessor implements TelemetryEventProcessor {

    private final Set<Long> storedKeys = new LinkedHashSet<>();

    @Override
    public void processSafetrustRead(SafetrustEventDto dto, String ingressMode, long tIngressNano) {
        storedKeys.add(dto.getEventKey());
    }

    /** This reference processor maps every credential to a card straight away, so nothing is ever left unmapped. */
    @Override
    public boolean reprocessUnmappedLocalRead(String eventId) {
        return false;
    }

    @Override
    public List<String> unmappedLocalReadIds(int limit) {
        return List.of();
    }

    @Override
    public int countUnmappedLocalReads() {
        return 0;
    }

    int stored() {
        return storedKeys.size();
    }
}
