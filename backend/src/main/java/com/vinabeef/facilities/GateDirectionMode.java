package com.vinabeef.facilities;

import java.util.Locale;
import java.util.Optional;

/**
 * The direction rule of a gate, stored in {@code gates.direction_mode}.
 *
 * <ul>
 *   <li>{@link #BIDIRECTIONAL}: animals pass both ways. With an inner and an outer reader the direction is the
 *       order of the two reads (delta time); with a single reader it follows the animal's last known presence.</li>
 *   <li>{@link #IN_ONLY}: a return gate. Whatever reader sees the animal, the animal is coming back IN.</li>
 *   <li>{@link #OUT_ONLY}: an exit gate. Whatever reader sees the animal, the animal is going OUT to pasture.</li>
 * </ul>
 */
public enum GateDirectionMode {
    BIDIRECTIONAL(null),
    IN_ONLY("IN"),
    OUT_ONLY("OUT");

    private final String fixedDirection;

    GateDirectionMode(String fixedDirection) {
        this.fixedDirection = fixedDirection;
    }

    public boolean isOneWay() {
        return fixedDirection != null;
    }

    /** "IN" for an IN_ONLY gate, "OUT" for an OUT_ONLY gate, null when the direction is not fixed by the gate. */
    public String fixedDirection() {
        return fixedDirection;
    }

    /** Strict parsing for user input. Accepts any case and "-" or space instead of "_". */
    public static Optional<GateDirectionMode> tryParse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (GateDirectionMode mode : values()) {
            if (mode.name().equals(key)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /** Lenient parsing for stored values: a missing or unknown value has always meant BIDIRECTIONAL. */
    public static GateDirectionMode parseOrDefault(String raw) {
        return tryParse(raw).orElse(BIDIRECTIONAL);
    }
}
