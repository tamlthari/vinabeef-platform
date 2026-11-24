package com.vinabeef.movement;

/**
 * Decides what one read of a tag at one reader of an inner/outer pair means, from the movements the animal already
 * has at that gate.
 *
 * <p>A barn gate has an inner reader (barn side) and an outer reader (pasture side). The rules:
 * <ul>
 *   <li><b>A read is always recorded.</b> The first read of an animal at one reader of the pair is a movement marked
 *       {@code HALF_CROSSING}: the animal passed one reader and has not (yet) passed the other. Nothing limits how long a half
 *       crossing may wait.</li>
 *   <li><b>The other reader completes it.</b> The next read at the <em>other</em> reader turns the half crossing into a
 *       complete crossing, however much later it comes. The order of the two reads (event time) is the direction:
 *       inner first and then outer is {@code OUT}, outer first and then inner is {@code IN}. This is the delta-time rule.</li>
 *   <li><b>The same reader again is a second pass.</b> If the animal is read at the same reader again, after the repeat-read
 *       time, and the other reader never saw it, that is a new half crossing whose direction is the opposite of the
 *       animal's last one (IN becomes OUT, OUT becomes IN).</li>
 *   <li><b>Repeat reads are not passes.</b> A read at the same reader within the repeat-read time of a read already
 *       recorded is one tap. Reads within the repeat-read time of a completed crossing are the animal still walking
 *       away from the readers.</li>
 * </ul>
 *
 * <p>The decision uses event times (the reader's own clock), so a read that arrives late, for example from an edge
 * backlog, is placed by when it happened: it completes the half crossing next to it in time, on either side.
 *
 * <p>The class is pure logic: it holds no state, reads no clock and touches no database.
 */
public final class GateCrossingResolver {

    private GateCrossingResolver() {
    }

    public enum Side {
        INNER, OUTER;

        /** Returns the side for {@code INNER}/{@code OUTER} (any case) or {@code null}. */
        public static Side parse(String value) {
            if (value == null) {
                return null;
            }
            try {
                return Side.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    /** One read of a tag at one reader. {@code eventId} is carried through for the evidence trail. */
    public record Sighting(Side side, long eventMs, String eventId) {
    }

    /**
     * A movement of this animal at this gate that is already stored.
     *
     * @param movementId the stored movement
     * @param half       true for a half crossing
     * @param side       for a half crossing: the reader that saw the animal. {@code null} for a half crossing that can no
     *                   longer be completed (the readers of the gate were changed after it), and for complete crossings
     * @param startMs    event time of the first read of the movement (a half crossing or a one-read movement: {@code atMs})
     * @param atMs       event time of the last read of the movement
     */
    public record Known(String movementId, boolean half, Side side, long startMs, long atMs) {
    }

    public enum Action {
        /** Nothing new: an echo of a read or a crossing that is already recorded. */
        IGNORE,
        /** Record the read as a movement marked HALF_CROSSING. */
        HALF,
        /** The other reader saw the animal before or after: the half crossing {@code partner} becomes a complete crossing. */
        COMPLETE
    }

    /**
     * @param action       what to do with the incoming read
     * @param direction    {@code "IN"} or {@code "OUT"} when {@code action} is {@link Action#COMPLETE}, else {@code null}
     * @param partner      for {@link Action#COMPLETE}: the half crossing that is completed, else {@code null}
     * @param partnerFirst for {@link Action#COMPLETE}: true when the half crossing was read before the incoming read
     * @param secondPass   for {@link Action#HALF}: true when the animal was already seen at this same reader and the other
     *                     reader never saw it, so the direction is turned around
     */
    public record Decision(Action action, String direction, Known partner, boolean partnerFirst, boolean secondPass) {
    }

    private static final Decision IGNORE = new Decision(Action.IGNORE, null, null, false, false);
    private static final Decision FIRST_HALF = new Decision(Action.HALF, null, null, false, false);
    private static final Decision SECOND_PASS = new Decision(Action.HALF, null, null, false, true);

    /**
     * @param previous   the animal's latest movement at this gate at or before the incoming read, or null
     * @param next       the animal's earliest movement at this gate after the incoming read, or null (only a read that
     *                   arrives late has one)
     * @param incoming   the read that has just arrived
     * @param debounceMs reads at the same reader closer than this are one tap
     */
    public static Decision decide(Known previous, Known next, Sighting incoming, long debounceMs) {
        long at = incoming.eventMs();

        // The animal is still being read as it walks away from the readers: part of a crossing that is complete.
        for (Known k : new Known[]{previous, next}) {
            if (k != null && !k.half() && at > k.startMs() - debounceMs && at < k.atMs() + debounceMs) {
                return IGNORE;
            }
        }
        // The same reader reporting the same tap again.
        for (Known k : new Known[]{previous, next}) {
            if (k != null && k.half() && k.side() == incoming.side() && Math.abs(at - k.atMs()) < debounceMs) {
                return IGNORE;
            }
        }

        // The other reader saw the animal: that is the second half. The earlier half crossing wins when both sides have one.
        if (previous != null && previous.half() && previous.side() != null && previous.side() != incoming.side()) {
            return complete(previous, true, incoming.side());
        }
        if (next != null && next.half() && next.side() != null && next.side() != incoming.side()) {
            return complete(next, false, incoming.side());
        }

        boolean secondPass = previous != null && previous.half() && previous.side() == incoming.side();
        return secondPass ? SECOND_PASS : FIRST_HALF;
    }

    /** Inner first and then outer is OUT; outer first and then inner is IN. */
    private static Decision complete(Known partner, boolean partnerFirst, Side incomingSide) {
        Side firstSide = partnerFirst ? partner.side() : incomingSide;
        String direction = firstSide == Side.INNER ? "OUT" : "IN";
        return new Decision(Action.COMPLETE, direction, partner, partnerFirst, false);
    }
}
