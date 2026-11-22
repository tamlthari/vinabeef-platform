package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserChangeGuardTest {

    private static final UserChangeGuard.Actor ADMIN = new UserChangeGuard.Actor("admin", "SYSTEM_ADMIN", null);
    private static final UserChangeGuard.Actor DN_MANAGER = new UserChangeGuard.Actor("manager_dongnai", "FARM_MANAGER", "FARM-DONG-NAI");

    private static UserChangeGuard.Target target(String name, String role, String farm, boolean enabled) {
        return new UserChangeGuard.Target(name, role, farm, enabled);
    }

    @Test
    void anAdministratorCanEditAnotherAccount() {
        assertNull(UserChangeGuard.denyChange(ADMIN, target("sup1", "BARN_SUPERVISOR", "FARM-DAK-LAK", true),
                "FARM_MANAGER", true, 1));
    }

    @Test
    void nobodyCanDemoteOrDisableOrDeleteThemself() {
        var self = target("admin", "SYSTEM_ADMIN", null, true);

        assertEquals(400, UserChangeGuard.denyChange(ADMIN, self, "FARM_MANAGER", true, 2).status());
        assertEquals(400, UserChangeGuard.denyChange(ADMIN, self, "SYSTEM_ADMIN", false, 2).status());
        assertEquals(400, UserChangeGuard.denyDelete(ADMIN, self, 2).status());
    }

    @Test
    void theLastEnabledAdministratorCannotBeDemotedDisabledOrDeleted() {
        var lastAdmin = target("root", "SYSTEM_ADMIN", null, true);
        var other = new UserChangeGuard.Actor("someone_else", "SYSTEM_ADMIN", null);

        assertEquals(409, UserChangeGuard.denyChange(other, lastAdmin, "EXECUTIVE", true, 1).status());
        assertEquals(409, UserChangeGuard.denyChange(other, lastAdmin, "SYSTEM_ADMIN", false, 1).status());
        assertEquals(409, UserChangeGuard.denyDelete(other, lastAdmin, 1).status());
        assertNull(UserChangeGuard.denyDelete(other, lastAdmin, 2));
    }

    @Test
    void aDisabledAdministratorDoesNotCountAsTheLastOne() {
        var disabledAdmin = target("old_admin", "SYSTEM_ADMIN", null, false);

        assertNull(UserChangeGuard.denyDelete(ADMIN, disabledAdmin, 1));
    }

    @Test
    void aFarmManagerStaysInsideTheirFarm() {
        var inside = target("sup_dn", "BARN_SUPERVISOR", "FARM-DONG-NAI", true);
        var outside = target("sup_dl", "BARN_SUPERVISOR", "FARM-DAK-LAK", true);
        var globalAccount = target("admin", "SYSTEM_ADMIN", null, true);

        assertNull(UserChangeGuard.denyChange(DN_MANAGER, inside, "BARN_SUPERVISOR", true, 1));
        assertEquals(403, UserChangeGuard.denyChange(DN_MANAGER, outside, "BARN_SUPERVISOR", true, 1).status());
        assertEquals(403, UserChangeGuard.denyDelete(DN_MANAGER, outside, 1).status());
        assertEquals(403, UserChangeGuard.denyDelete(DN_MANAGER, globalAccount, 2).status());
    }

    @Test
    void aFarmManagerCannotMakeAnAdministratorOrExecutive() {
        var inside = target("sup_dn", "BARN_SUPERVISOR", "FARM-DONG-NAI", true);

        assertEquals(403, UserChangeGuard.denyChange(DN_MANAGER, inside, "SYSTEM_ADMIN", true, 1).status());
        assertEquals(403, UserChangeGuard.denyChange(DN_MANAGER, inside, "EXECUTIVE", true, 1).status());
        assertTrue(UserChangeGuard.denyChange(DN_MANAGER, inside, "SYSTEM_ADMIN", true, 1).message().contains("cannot grant"));
    }
}
