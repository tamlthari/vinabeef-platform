package com.vinabeef.facilities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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

    @Column(name = "direction_mode")
    private String directionMode;

    /** Longest gap between the inner and the outer read that still counts as one crossing. */
    @Column(name = "pair_window_ms", nullable = false)
    private int pairWindowMs = 15000;

    /** Repeat reads at the same reader closer than this are treated as one tap. */
    @Column(name = "repeat_debounce_ms", nullable = false)
    private int repeatDebounceMs = 3000;
}