package com.vinabeef.movement;

import com.vinabeef.facilities.GateDirectionMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure rules that turn a gate's direction mode and an animal's presence into a direction and a headcount
 * change. No database, no Spring: everything here is covered by unit tests.
 */
final class GateDirectionPolicy {

    /** Direction from the order of the inner and the outer reader (delta time). BIDIRECTIONAL gate, two readers. */
    static final String PAIRED_READERS = "PAIRED_READERS";
    /** Direction from the animal's last presence. BIDIRECTIONAL gate, one reader. */
    static final String GATE_MODE = "GATE_MODE";
    /** Direction fixed by the gate: IN_ONLY is always IN, OUT_ONLY is always OUT. */
    static final String ONE_WAY_GATE = "ONE_WAY_GATE";

    static final String IN = "IN";
    static final String OUT = "OUT";
    static final String IN_BARN = "IN_BARN";
    static final String OUT_PASTURE = "OUT_PASTURE";

    /** A change of one barn's headcount. */
    record HeadcountMove(String barnId, int delta) { }

    private GateDirectionPolicy() { }

    /**
     * How the direction of a read at this gate is decided. A one-way gate wins over everything else: its readers
     * (one or two) only say that an animal is there, the gate says which way it is going. Delta time is only for
     * BIDIRECTIONAL gates, and only when the reader is one side of an inner/outer pair.
     */
    static String methodFor(GateDirectionMode mode, boolean readerHasSide) {
        if (mode.isOneWay()) {
            return ONE_WAY_GATE;
        }
        return readerHasSide ? PAIRED_READERS : GATE_MODE;
    }

    /** Direction of a read that is not part of a delta-time pair. */
    static String singleReadDirection(GateDirectionMode mode, String previousStatus) {
        String fixed = mode.fixedDirection();
        if (fixed != null) {
            return fixed;
        }
        // BIDIRECTIONAL with one reader: an animal that is out comes in, anything else goes out.
        return OUT_PASTURE.equals(previousStatus) ? IN : OUT;
    }

    /** Presence the animal has after crossing in this direction. */
    static String presenceAfter(String direction) {
        return IN.equals(direction) ? IN_BARN : OUT_PASTURE;
    }

    /**
     * Headcount changes for one crossing. The headcount counts animals that are in a barn, so it only moves when
     * the animal really changes between "in a barn" and "not in a barn" (or between two barns). A read that agrees
     * with what is already known (an IN for an animal that is already in, an OUT for an animal that is already
     * out) is still a movement, but it must not move the headcount.
     *
     * @param previousBarnId the barn the animal was last recorded in (where it is counted while IN_BARN)
     */
    static List<HeadcountMove> headcountMoves(String direction, String previousStatus,
                                              String previousBarnId, String gateBarnId) {
        boolean wasIn = IN_BARN.equals(previousStatus);
        if (IN.equals(direction)) {
            if (!wasIn) {
                return List.of(new HeadcountMove(gateBarnId, +1));
            }
            if (previousBarnId == null || previousBarnId.equals(gateBarnId)) {
                return List.of();
            }
            return List.of(new HeadcountMove(previousBarnId, -1), new HeadcountMove(gateBarnId, +1));
        }
        if (!wasIn) {
            return List.of();
        }
        return List.of(new HeadcountMove(previousBarnId != null ? previousBarnId : gateBarnId, -1));
    }

    /**
     * The headcount change that turns a movement applied as {@code appliedDirection} into one that went
     * {@code realDirection}, from the same starting point ({@code previousStatus}, {@code previousBarnId}). It is what
     * a half crossing needs when the other reader completes it and the order of the two reads says the animal went the
     * other way: the first guess is taken back and the real direction applied, so the barns end up exactly as if the
     * real direction had been known from the start. Empty when nothing has to move.
     */
    static List<HeadcountMove> correction(String appliedDirection, String realDirection, String previousStatus,
                                          String previousBarnId, String gateBarnId) {
        Map<String, Integer> net = new LinkedHashMap<>();
        for (HeadcountMove m : headcountMoves(appliedDirection, previousStatus, previousBarnId, gateBarnId)) {
            net.merge(m.barnId(), -m.delta(), Integer::sum);
        }
        for (HeadcountMove m : headcountMoves(realDirection, previousStatus, previousBarnId, gateBarnId)) {
            net.merge(m.barnId(), m.delta(), Integer::sum);
        }
        return net.entrySet().stream()
                .filter(e -> e.getValue() != 0)
                .map(e -> new HeadcountMove(e.getKey(), e.getValue()))
                .toList();
    }
}
