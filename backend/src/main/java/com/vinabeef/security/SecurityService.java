package com.vinabeef.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service("securityService")
@RequiredArgsConstructor
@Slf4j
public class SecurityService {

    private final UserRepository userRepository;

    /**
     * Evaluates if the authenticated user has access to a specific farm.
     * System Admins and Executives have global cross-farm authority.
     * Farm Managers and Supervisors are restricted to their assigned farm scope.
     */
    public boolean isFarmAssigned(Authentication authentication, String farmId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        String username = authentication.getName();
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !user.isEnabled()) {
            return false;
        }

        // Global roles bypass single-farm restriction
        if ("SYSTEM_ADMIN".equals(user.getRole()) || "EXECUTIVE".equals(user.getRole())) {
            return true;
        }

        // Farm-scoped check[cite: 10, 40]
        boolean assigned = user.getAssignedFarmIds().contains(farmId);
        if (!assigned) {
            log.warn("Access Denied: User '{}' attempted to access unauthorized farm '{}'", username, farmId);
        }
        return assigned;
    }
}