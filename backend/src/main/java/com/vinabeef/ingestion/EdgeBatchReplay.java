package com.vinabeef.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Replays events that a barn gateway buffered while the Internet was down: oldest reader time first (so the inner
 * and outer read of one crossing stay in order), one event at a time, through the {@link TelemetryEventProcessor}
 * port. It knows nothing about HTTP or databases, so any implementation of the port can stand behind it.
 *
 * <p>The port's contract is what the gateway relies on: an event the processor accepts without throwing is stored
 * (a duplicate counts as accepted), so the gateway may delete it; an event that throws must be kept and sent again.
 * One failing event never stops the ones after it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EdgeBatchReplay {

    /** What happened to one event. {@code error} is null when it was accepted. */
    public record EventResult(Long eventKey, boolean ok, String error) {
    }

    public record Outcome(int processed, int failed, double elapsedMs, List<EventResult> results) {
        public boolean allOk() {
            return failed == 0;
        }
    }

    private final TelemetryEventProcessor processor;

    /** Replays a copy of {@code events} in reader-time order; the caller's list is not changed. */
    public Outcome replay(List<SafetrustEventDto> events, String ingressMode) {
        List<SafetrustEventDto> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparingLong(e -> e.getTimestamp() != null ? e.getTimestamp() : 0L));

        int processed = 0;
        int failed = 0;
        List<EventResult> results = new ArrayList<>(ordered.size());
        long tStart = System.nanoTime();

        for (SafetrustEventDto dto : ordered) {
            try {
                processor.processSafetrustRead(dto, ingressMode, System.nanoTime());
                results.add(new EventResult(dto.getEventKey(), true, null));
                processed++;
            } catch (Exception ex) {
                failed++;
                results.add(new EventResult(dto.getEventKey(), false, String.valueOf(ex.getMessage())));
                log.error("[{}] Failed replaying eventKey={}: {}", ingressMode, dto.getEventKey(), ex.getMessage());
            }
        }

        double totalMs = Math.round((System.nanoTime() - tStart) / 10000.0) / 100.0;
        log.info("[{}] Replayed {} events ({} failed) in {} ms", ingressMode, processed, failed, totalMs);
        return new Outcome(processed, failed, totalMs, results);
    }
}
