package com.vinabeef.security.adapter;

import com.vinabeef.security.FarmAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * The bean that web security expressions call ({@code @securityService.isFarmAssigned(authentication, #farmId)}).
 * It only translates Spring Security's {@link Authentication} into a username; the rule is in
 * {@link FarmAssignmentService}. The bean name is fixed because the expressions use it.
 */
@Service("securityService")
@RequiredArgsConstructor
public class SecurityService {

    private final FarmAssignmentService farmAssignment;

    public boolean isFarmAssigned(Authentication authentication, String farmId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return farmAssignment.isFarmAssigned(authentication.getName(), farmId);
    }
}
