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
}