package com.vinabeef.security;

import com.vinabeef.security.port.AccountLookup;
import com.vinabeef.security.port.AccountLookup.AccountView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Whether a signed-in person may act on a farm. System admins and executives have global authority; farm managers
 * and supervisors are limited to the farms they are assigned to. Web security expressions reach this rule through
 * {@code security.adapter.SecurityService}, which only passes the signed-in username in.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FarmAssignmentService {

    private final AccountLookup accounts;

    public boolean isFarmAssigned(String username, String farmId) {
        AccountView user = accounts.byUsername(username).orElse(null);
        if (user == null || !user.enabled()) {
            return false;
        }

        // Global roles bypass single-farm restriction
        if ("SYSTEM_ADMIN".equals(user.role()) || "EXECUTIVE".equals(user.role())) {
            return true;
        }

        boolean assigned = user.assignedFarmIds().contains(farmId);
        if (!assigned) {
            log.warn("Access Denied: User '{}' attempted to access unauthorized farm '{}'", username, farmId);
        }
        return assigned;
    }
}
