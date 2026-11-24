package com.vinabeef.movement.port;

import com.vinabeef.movement.GateCrossingResolver.Known;
import com.vinabeef.movement.GateCrossingResolver.Side;

import java.util.Optional;

/**
 * Output port of the movement use case: which reader sits on which side of which gate, the movements an animal
 * already has at a gate (to find the half crossing a read completes), and reader status. Owned by {@code movement};
 * the JDBC implementation lives in {@code movement.adapter}.
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

    /**
     * The animal's movements at this gate on either side of {@code eventMs}: the latest one at or before it and the
     * earliest one after it. Half crossings are movements, so this is where a read finds the half crossing it
     * completes. It compares event time, so a replayed backlog gives the same answer as live ingestion.
     */
    Neighbours neighbours(String animalId, String gateId, long eventMs);

    /** Either side may be null: the animal has no movement at the gate before, or after, that time. */
    record Neighbours(Known previous, Known next) {
    }

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
