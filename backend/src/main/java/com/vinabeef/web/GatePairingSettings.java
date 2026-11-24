package com.vinabeef.web;

/**
 * Checks the one timing setting of a gate before it is written: the repeat-read time, how close two reads at the same
 * reader must be to count as ONE tap. Pure logic, no database, so the rule can be unit tested.
 *
 * <p>There is no setting for how far apart the two readers of a pair may see an animal. A read at one reader is
 * recorded at once as a HALF_CROSSING and waits for the other reader as long as it takes, so a slow animal (or a person
 * walking a card from one reader to the other) never loses its crossing.
 */
final class GatePairingSettings {

    /** Gate default, the same as the column default in {@code gates}. */
    static final int DEFAULT_REPEAT_DEBOUNCE_MS = 3_000;

    static final int MIN_REPEAT_DEBOUNCE_MS = 500;
    static final int MAX_REPEAT_DEBOUNCE_MS = 60_000;

    /** The value to store, or an {@code error} message for an HTTP 400. */
    record Result(int repeatDebounceMs, String error) {
        boolean ok() {
            return error == null;
        }
    }

    private GatePairingSettings() { }

    /**
     * @param currentDebounceMs the gate's stored repeat debounce
     * @param reqDebounceMs     requested repeat debounce in milliseconds; null keeps the current value
     */
    static Result resolve(int currentDebounceMs, Integer reqDebounceMs) {
        int debounce = reqDebounceMs != null ? reqDebounceMs : currentDebounceMs;
        if (debounce < MIN_REPEAT_DEBOUNCE_MS || debounce > MAX_REPEAT_DEBOUNCE_MS) {
            return new Result(0, "The repeat-read time must be between " + seconds(MIN_REPEAT_DEBOUNCE_MS) + " and "
                    + seconds(MAX_REPEAT_DEBOUNCE_MS) + " (it was " + seconds(debounce) + ").");
        }
        return new Result(debounce, null);
    }

    /** {@code 31693} becomes {@code "31.693 s"}, {@code 15000} becomes {@code "15 s"}, {@code 500} becomes {@code "0.5 s"}. */
    static String seconds(long ms) {
        return java.math.BigDecimal.valueOf(ms).movePointLeft(3).stripTrailingZeros().toPlainString() + " s";
    }
}
