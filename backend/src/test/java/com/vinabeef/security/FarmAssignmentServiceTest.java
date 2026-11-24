package com.vinabeef.security;

import com.vinabeef.security.port.AccountLookup;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who may act on a farm: global roles anywhere, everybody else only on the farms they are assigned to. */
class FarmAssignmentServiceTest {

    private final Map<String, AccountLookup.AccountView> accounts = new HashMap<>();

    private FarmAssignmentService service() {
        AccountLookup lookup = username -> Optional.ofNullable(accounts.get(username));
        return new FarmAssignmentService(lookup);
    }

    private void account(String username, String role, boolean enabled, String... farms) {
        accounts.put(username, new AccountLookup.AccountView(username, role, enabled, List.of(farms)));
    }

    @Test
    void anAdminAndAnExecutiveMayActOnAnyFarm() {
        account("root", "SYSTEM_ADMIN", true);
        account("boss", "EXECUTIVE", true);
        assertTrue(service().isFarmAssigned("root", "FARM-9"));
        assertTrue(service().isFarmAssigned("boss", "FARM-9"));
    }

    @Test
    void aFarmManagerMayActOnlyOnTheirOwnFarms() {
        account("mgr", "FARM_MANAGER", true, "FARM-1", "FARM-2");
        assertTrue(service().isFarmAssigned("mgr", "FARM-2"));
        assertFalse(service().isFarmAssigned("mgr", "FARM-3"));
    }

    @Test
    void aDisabledAccountMayNotEvenIfItIsAnAdmin() {
        account("old", "SYSTEM_ADMIN", false);
        assertFalse(service().isFarmAssigned("old", "FARM-1"));
    }

    @Test
    void anUnknownAccountMayNot() {
        assertFalse(service().isFarmAssigned("nobody", "FARM-1"));
    }

    @Test
    void aFarmManagerWithNoFarmsMayNotActOnAnyFarm() {
        account("new", "FARM_MANAGER", true);
        assertFalse(service().isFarmAssigned("new", "FARM-1"));
    }
}
