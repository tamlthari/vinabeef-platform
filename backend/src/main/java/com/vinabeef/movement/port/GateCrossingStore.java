package com.vinabeef.movement.port;

import com.vinabeef.movement.GateCrossingResolver.Side;
import com.vinabeef.movement.GateCrossingResolver.Sighting;

import java.util.List;
import java.util.Optional;

/**
 * Output port of the movement use case: which reader sits on which side of which gate, the unpaired sightings
 * waiting for the other reader, and reader status. Owned by {@code movement}; the JDBC implementation lives in
 * {@code movement.adapter}.
 *
 * <p>Every method joins the caller's transaction.
 */
public interface GateCrossingStore {

    /** A reader that belongs to a gate, and the side of the gate it is mounted on. */
    record ReaderBinding(String readerName, String gateId, Side side) {
    }

    Optional<ReaderBinding> findReader(String readerName);

    /**
     * Serialises concurrent events for one tag at one gate (for example a live MQTT message and an edge replay
     * batch arriving together) until the transaction ends.
     */
    void lockCrossing(String tagEpc, String gateId);

    /** Sightings of this tag at this gate that have no partner yet, with event time in [fromMs, toMs]. */
    List<Sighting> findUnpaired(String tagEpc, String gateId, long fromMs, long toMs);

    void addUnpaired(String tagEpc, String gateId, Sighting sighting);

    /**
     * Removes the paired sighting and the other unpaired reads that led up to the same pass (earlier than the
     * later sighting and within the window), so a tag that lingers between the readers is counted once.
     */
    void consume(String tagEpc, String gateId, String partnerEventId, long laterMs, long windowMs);

    /** Forgets unpaired sightings of this tag at this gate that are older than {@code beforeMs}. */
    void pruneBefore(String tagEpc, String gateId, long beforeMs);

    /** True when a paired-reader movement of this animal at this gate happened within {@code debounceMs} of {@code eventMs}. */
    boolean crossingCompletedNear(String animalId, String gateId, long eventMs, long debounceMs);

    /**
     * True when this animal already has a movement at this gate within {@code debounceMs} of {@code eventMs}, by any
     * method. Used for gates without a reader pair, where an animal standing at the reader reports several times.
     * It compares event time, so a replayed backlog gives the same answer as live ingestion.
     */
    boolean movementNear(String animalId, String gateId, long eventMs, long debounceMs);

    /** Remembers that a registered reader produced an event at {@code eventMs} (hardware time). */
    void touchReader(String readerName, long eventMs);

    /**
     * Applies a reader connect or disconnect. An older event never overwrites a newer status, so an edge
     * replay that delivers a stale "connected" after a fresh "disconnected" is harmless.
     */
    void setConnection(String readerName, String status, String ipAddress, long eventMs);
}
