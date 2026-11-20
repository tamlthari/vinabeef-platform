package com.vinabeef.movement;

import java.util.List;

/**
 * Decides which way an animal moved from the ORDER in which the two readers of a gate saw its tag.
 *
 * <p>A barn gate has an inner reader (barn side) and an outer reader (pasture side). The same tag seen at
 * the inner reader first and then at the outer reader is leaving ({@code OUT}); outer first and then inner is
 * coming back ({@code IN}). A single sighting is only half a crossing: it waits in a pool of unpaired
 * sightings until the other reader sees the same tag.
 *
 * <p>The decision depends only on event times (the reader's own clock), never on the order in which events
 * arrive. After an outage the edge gateway replays a backlog, and a retried event can arrive after newer
 * ones; the same pairs are found either way.
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

    public enum Action {
        /** No partner yet: keep this sighting in the pool and wait for the other reader. */
        REMEMBER,
        /** An echo of a sighting or a crossing that is already known: store nothing. */
        IGNORE,
        /** The other reader saw the tag within the window: a movement happened. */
        COMPLETE
    }

    /**
     * @param action    what to do with the incoming sighting
     * @param direction {@code "IN"} or {@code "OUT"} when {@code action} is {@link Action#COMPLETE}, else {@code null}
     * @param first     for {@link Action#COMPLETE}: the earlier of the two sightings, else {@code null}
     * @param later     for {@link Action#COMPLETE}: the later of the two sightings, else {@code null}
     * @param partner   for {@link Action#COMPLETE}: the pooled sighting that was paired with the incoming one
     *                  (it must be removed from the pool), else {@code null}
     */
    public record Decision(Action action, String direction, Sighting first, Sighting later, Sighting partner) {
    }

    private static final Decision REMEMBER = new Decision(Action.REMEMBER, null, null, null, null);
    private static final Decision IGNORE = new Decision(Action.IGNORE, null, null, null, null);

    /**
     * @param unpaired    sightings of this tag at this gate that have no partner yet (any order)
     * @param incoming    the sighting that has just arrived
     * @param justCrossed true when a movement of this animal at this gate was completed within {@code debounceMs}
     *                    of {@code incoming}: the tag is still being read as it walks away from the readers
     * @param windowMs    longest gap between the two readers that still counts as one crossing
     * @param debounceMs  repeat reads at the same reader closer than this are one tap
     */
    public static Decision decide(List<Sighting> unpaired, Sighting incoming, boolean justCrossed,
                                  long windowMs, long debounceMs) {
        if (justCrossed) {
            return IGNORE;
        }

        Sighting partner = null;
        long partnerGap = Long.MAX_VALUE;
        for (Sighting s : unpaired) {
            long gap = Math.abs(incoming.eventMs() - s.eventMs());
            if (s.side() == incoming.side()) {
                if (gap < debounceMs) {
                    return IGNORE;
                }
            } else if (gap <= windowMs && (partner == null || gap < partnerGap
                    || (gap == partnerGap && isBefore(s, partner)))) {
                partner = s;
                partnerGap = gap;
            }
        }
        if (partner == null) {
            return REMEMBER;
        }

        boolean incomingFirst = incoming.eventMs() < partner.eventMs();
        Sighting first = incomingFirst ? incoming : partner;
        Sighting later = incomingFirst ? partner : incoming;
        String direction = first.side() == Side.INNER ? "OUT" : "IN";
        return new Decision(Action.COMPLETE, direction, first, later, partner);
    }

    /** Equal gaps are broken the same way every time (earlier read, then event id), whatever the list order. */
    private static boolean isBefore(Sighting a, Sighting b) {
        if (a.eventMs() != b.eventMs()) {
            return a.eventMs() < b.eventMs();
        }
        return a.eventId().compareTo(b.eventId()) < 0;
    }
}
