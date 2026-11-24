package com.vinabeef.security;

import com.vinabeef.security.port.AuthenticatedUser;
import com.vinabeef.security.port.ScopeDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Reads the signed-in person's scope and answers whether they may act on a barn. Controllers depend on the two
 * small interfaces, {@link CurrentScopeProvider} and {@link BarnAccessPolicy}, not on this class; this class
 * depends on two ports, not on the security framework or the database.
 */
@Service
@RequiredArgsConstructor
public class RbacScopeService implements CurrentScopeProvider, BarnAccessPolicy {

    private final AuthenticatedUser signedIn;
    private final ScopeDirectory directory;

    @Override
    public UserScope getCurrentScope() {
        Optional<String> who = signedIn.username();
        if (who.isEmpty()) {
            return new UserScope("anonymous", "NONE", null, List.of());
        }

        String username = who.get();
        Optional<ScopeDirectory.Account> account = directory.findAccount(username);
        if (account.isEmpty()) {
            return new UserScope(username, "NONE", null, List.of());
        }

        String role = account.get().role();
        List<String> barnIds = List.of();
        if ("BARN_SUPERVISOR".equals(role)) {
            barnIds = directory.barnsAssignedTo(username);
        }

        return new UserScope(username, role, account.get().farmId(), barnIds);
    }

    @Override
    public boolean canAccessBarnId(UserScope scope, String barnId) {
        if (scope.isGlobal()) return true;
        if (barnId == null || barnId.isBlank()) return false;
        if (scope.isBarnScoped()) {
            return scope.barnIds().contains(barnId);
        }
        if (scope.isFarmScoped()) {
            Optional<String> farm = directory.farmOfBarn(barnId);
            return farm.isPresent() && farm.get().equals(scope.farmId());
        }
        return false;
    }
}
