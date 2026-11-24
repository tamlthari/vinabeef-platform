package com.vinabeef.web;

/**
 * Checks the two timing settings of a gate before they are written: how long the two readers of a pair may be apart
 * and still count as ONE crossing, and how close two reads at the same reader must be to count as ONE tap. Pure
 * logic, no database, so the rules can be unit tested.
 *
 * <p>The window has to be set to how the animals really move through the gate. A cow that pauses between the inner
 * and the outer reader (or a test where a person walks a card from one reader to the other) can take much longer
 * than the 15 s default. A read that finds no partner inside the window stays an unpaired "half crossing" and no
 * movement is recorded.
 */
final class GatePairingSettings {

    /** Gate defaults, the same as the column defaults in {@code gates}. */
    static final int DEFAULT_PAIR_WINDOW_MS = 15_000;
    static final int DEFAULT_REPEAT_DEBOUNCE_MS = 3_000;

    static final int MIN_PAIR_WINDOW_MS = 1_000;
    static final int MAX_PAIR_WINDOW_MS = 300_000;
    static final int MIN_REPEAT_DEBOUNCE_MS = 500;
    static final int MAX_REPEAT_DEBOUNCE_MS = 60_000;

    /** The values to store, or an {@code error} message for an HTTP 400. */
    record Result(int pairWindowMs, int repeatDebounceMs, String error) {
        boolean ok() {
            return error == null;
        }
    }

    private GatePairingSettings() { }

    /**
     * @param currentWindowMs   the gate's stored pair window
     * @param currentDebounceMs the gate's stored repeat debounce
     * @param reqWindowMs       requested pair window in milliseconds; null keeps the current value
     * @param reqDebounceMs     requested repeat debounce in milliseconds; null keeps the current value
     */
    static Result resolve(int currentWindowMs, int currentDebounceMs, Integer reqWindowMs, Integer reqDebounceMs) {
        int window = reqWindowMs != null ? reqWindowMs : currentWindowMs;
        int debounce = reqDebounceMs != null ? reqDebounceMs : currentDebounceMs;

        if (window < MIN_PAIR_WINDOW_MS || window > MAX_PAIR_WINDOW_MS) {
            return fail("The pairing window must be between " + seconds(MIN_PAIR_WINDOW_MS) + " and "
                    + seconds(MAX_PAIR_WINDOW_MS) + " (it was " + seconds(window) + ").");
        }
        if (debounce < MIN_REPEAT_DEBOUNCE_MS || debounce > MAX_REPEAT_DEBOUNCE_MS) {
            return fail("The repeat-read time must be between " + seconds(MIN_REPEAT_DEBOUNCE_MS) + " and "
                    + seconds(MAX_REPEAT_DEBOUNCE_MS) + " (it was " + seconds(debounce) + ").");
        }
        if (window < debounce) {
            return fail("The pairing window (" + seconds(window) + ") cannot be shorter than the repeat-read time ("
                    + seconds(debounce) + ").");
        }
        return new Result(window, debounce, null);
    }

    private static Result fail(String message) {
        return new Result(0, 0, message);
    }

    /** {@code 31693} becomes {@code "31.693 s"}, {@code 15000} becomes {@code "15 s"}, {@code 500} becomes {@code "0.5 s"}. */
    static String seconds(long ms) {
        return java.math.BigDecimal.valueOf(ms).movePointLeft(3).stripTrailingZeros().toPlainString() + " s";
    }
}
