package com.vinabeef.movement.port;

import com.vinabeef.ingestion.SafetrustEventDto;

import java.util.List;
import java.util.Optional;

/** Output port: the raw record of every reader event, kept exactly once. */
public interface ReadEventLog {

    /** A stored USB read whose credential number mapped to no card when it arrived. */
    record UnmappedRead(Long eventKey, String readerName, String credential, long hardwareMs, String sourceDevice) {
    }

    /**
     * Stores the raw event. Returns false when an event with the same EVENT_KEY is already stored, which is how a
     * replay of one event (edge outbox, second subscriber, Safetrust redelivery) becomes a no-op.
     */
    boolean storeIfNew(String eventId, SafetrustEventDto dto, String eventClass,
                       long hardwareMs, String ingressMode, String cardResolvedBy);

    /** The stored USB read with this id, when it is still unmapped. */
    Optional<UnmappedRead> findUnmapped(String eventId);

    /** Records that the card of a stored unmapped USB read is known now, and how it was found. */
    void markResolved(String eventId, String cardUuid, String resolvedBy);

    /** The oldest stored USB reads that still map to no card (at most {@code limit}). */
    List<String> unmappedIds(int limit);

    int countUnmapped();
}
