package com.vinabeef.ingestion;

/** The reference implementation passes the contract; a database-backed processor is held to the same rules by extending the contract. */
class InMemoryTelemetryProcessorContractTest extends TelemetryEventProcessorContract {

    @Override
    TelemetryEventProcessor newProcessor() {
        return new InMemoryTelemetryProcessor();
    }

    @Override
    int storedEvents(TelemetryEventProcessor processor) {
        return ((InMemoryTelemetryProcessor) processor).stored();
    }
}
