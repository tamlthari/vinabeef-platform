package com.vinabeef.web;

import com.vinabeef.facilities.GateDirectionMode;

import java.util.Objects;

/**
 * Works out what a reader registration or edit asks for, before anything is written: which direction mode the gate
 * ends up with, which readers it has, and whether the inner/outer pair changes. Pure logic, no database, so the
 * rules that keep a gate consistent can be unit tested.
 *
 * <p>Rules: a one-way gate (IN_ONLY / OUT_ONLY) has one reader because the gate, not the order of two readers,
 * decides the direction. An inner/outer pair therefore only exists on a BIDIRECTIONAL gate.
 */
final class GateReaderPlan {

    /** The state the gate should have after the change. {@code outer} is null when the gate has a single reader. */
    record Target(String primary, String outer, GateDirectionMode mode, boolean pairChanged) { }

    /** Either a {@code target} or an HTTP status with a message. */
    record Result(Target target, int status, String error) {
        boolean ok() {
            return target != null;
        }
    }

    private GateReaderPlan() { }

    static final String MODES = "BIDIRECTIONAL, IN_ONLY, OUT_ONLY";

    /**
     * @param currentPrimary the gate's current reader (the inner reader of a pair), null for a new gate
     * @param currentOuter   the current outer reader, null when the gate has none
     * @param currentMode    the current direction mode, null for a new gate
     * @param reqPrimary     requested reader name; null or blank keeps the current one
     * @param reqOuter       requested outer reader; null keeps the current one, blank removes it
     * @param reqMode        requested direction mode; null or blank keeps the current one
     */
    static Result plan(String currentPrimary, String currentOuter, GateDirectionMode currentMode,
                       String reqPrimary, String reqOuter, String reqMode) {
        GateDirectionMode mode = currentMode != null ? currentMode : GateDirectionMode.BIDIRECTIONAL;
        if (reqMode != null && !reqMode.isBlank()) {
            var parsed = GateDirectionMode.tryParse(reqMode);
            if (parsed.isEmpty()) {
                return fail(400, "Direction mode must be one of " + MODES + ".");
            }
            mode = parsed.get();
        }

        String primary = reqPrimary != null && !reqPrimary.isBlank() ? reqPrimary.trim() : currentPrimary;
        if (primary == null) {
            return fail(400, "Hardware Reader Name (e.g. DrEBE6) is required");
        }

        boolean outerRequested = reqOuter != null && !reqOuter.isBlank();
        String outer = reqOuter == null ? currentOuter : (reqOuter.isBlank() ? null : reqOuter.trim());

        if (outer != null && outer.equals(primary)) {
            return fail(400, "The inner and outer reader must be different readers.");
        }
        if (mode.isOneWay() && outer != null) {
            String name = mode.name();
            return outerRequested
                    ? fail(400, "A " + name + " gate has a single reader: the gate decides the direction, not the order of "
                            + "two readers. Leave the outer reader empty or choose BIDIRECTIONAL.")
                    : fail(409, "This gate has an inner/outer reader pair (outer reader '" + outer + "'). A " + name
                            + " gate has a single reader. Remove the outer reader in the same request "
                            + "(send an empty outerReaderName) or keep BIDIRECTIONAL.");
        }

        boolean pairChanged = !Objects.equals(outer, currentOuter)
                || (outer != null && !primary.equals(currentPrimary));
        return new Result(new Target(primary, outer, mode, pairChanged), 200, null);
    }

    private static Result fail(int status, String message) {
        return new Result(null, status, message);
    }
}
