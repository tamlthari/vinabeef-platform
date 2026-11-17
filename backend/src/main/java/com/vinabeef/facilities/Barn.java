package com.vinabeef.facilities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "barns")
@Getter
@Setter
public class Barn {
    @Id
    @Column(name = "barn_id")
    private String barnId;

    @Column(name = "farm_id", nullable = false)
    private String farmId;

    @Column(nullable = false)
    private String code;

    @Column(name = "company_key")
    private String companyKey;

    private int capacity;

    @Column(name = "current_headcount")
    private int currentHeadcount;
}