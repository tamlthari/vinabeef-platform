package com.vinabeef.facilities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "gates")
@Getter
@Setter
public class Gate {
    @Id
    @Column(name = "gate_id")
    private String gateId;

    @Column(name = "barn_id", nullable = false)
    private String barnId;

    @Column(nullable = false)
    private String label;

    @Column(name = "reader_name")
    private String readerName;

    /** BIDIRECTIONAL, IN_ONLY or OUT_ONLY, see {@link GateDirectionMode}. */
    @Column(name = "direction_mode", nullable = false)
    private String directionMode = "BIDIRECTIONAL";

    /** Repeat reads at the same reader closer than this are treated as one tap. */
    @Column(name = "repeat_debounce_ms", nullable = false)
    private int repeatDebounceMs = 3000;

    /** Set when the reader was retired. A retired gate keeps its history but no longer receives reads. */
    @Column(name = "retired_at")
    private Instant retiredAt;
}
