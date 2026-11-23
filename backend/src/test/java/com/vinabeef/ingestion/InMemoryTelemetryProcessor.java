package com.vinabeef.ingestion;

import java.util.LinkedHashSet;
import java.util.Set;

/** A reference implementation of the port for tests: it stores event keys in memory. */
class InMemoryTelemetryProcessor implements TelemetryEventProcessor {

    private final Set<Long> storedKeys = new LinkedHashSet<>();

    @Override
    public void processSafetrustRead(SafetrustEventDto dto, String ingressMode, long tIngressNano) {
        storedKeys.add(dto.getEventKey());
    }

    int stored() {
        return storedKeys.size();
    }
}
