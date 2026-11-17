package com.vinabeef.security;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {
    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String role; // 'SYSTEM_ADMIN', 'FARM_MANAGER', 'BARN_SUPERVISOR', 'EXECUTIVE'

    private boolean enabled;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_farm_scopes", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "farm_id")
    private Set<String> assignedFarmIds = new HashSet<>();
}