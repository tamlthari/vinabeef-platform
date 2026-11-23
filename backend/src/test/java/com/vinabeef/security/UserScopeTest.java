package com.vinabeef.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who may see which farm and barn. These rules decide every list and every write in the system, so they are pinned here. */
class UserScopeTest {

    private static final UserScope ADMIN = new UserScope("admin", "SYSTEM_ADMIN", null, List.of());
    private static final UserScope EXECUTIVE = new UserScope("exec", "EXECUTIVE", null, List.of());
    private static final UserScope MANAGER = new UserScope("manager_dongnai", "FARM_MANAGER", "FARM-DONG-NAI", List.of());
    private static final UserScope SUPERVISOR = new UserScope("supervisor_barn1", "BARN_SUPERVISOR", "FARM-DAK-LAK", List.of("BARN-DL-001", "BARN-DL-002"));
    private static final UserScope NOBODY = new UserScope("anonymous", "NONE", null, List.of());

    @Test
    void onlyAdministratorsAndExecutivesAreGlobal() {
        assertTrue(ADMIN.isGlobal());
        assertTrue(EXECUTIVE.isGlobal());
        assertFalse(MANAGER.isGlobal());
        assertFalse(SUPERVISOR.isGlobal());
        assertFalse(NOBODY.isGlobal());
    }

    @Test
    void theRoleDecidesTheKindOfScope() {
        assertTrue(MANAGER.isFarmScoped());
        assertFalse(SUPERVISOR.isFarmScoped());
        assertTrue(SUPERVISOR.isBarnScoped());
        assertFalse(MANAGER.isBarnScoped());
        assertFalse(NOBODY.isFarmScoped());
        assertFalse(NOBODY.isBarnScoped());
    }

    @Test
    void aGlobalAccountSeesEveryFarmAndBarn() {
        assertTrue(ADMIN.canAccessFarm("FARM-ANY"));
        assertTrue(EXECUTIVE.canAccessBarn("BARN-X", "FARM-ANY"));
    }

    @Test
    void aFarmManagerSeesOnlyTheirOwnFarm() {
        assertTrue(MANAGER.canAccessFarm("FARM-DONG-NAI"));
        assertFalse(MANAGER.canAccessFarm("FARM-DAK-LAK"));
        assertFalse(MANAGER.canAccessFarm(null));
        assertTrue(MANAGER.canAccessBarn("BARN-DN-001", "FARM-DONG-NAI"));
        assertFalse(MANAGER.canAccessBarn("BARN-DL-001", "FARM-DAK-LAK"));
    }

    @Test
    void aBarnSupervisorSeesOnlyTheirAssignedBarns() {
        assertTrue(SUPERVISOR.canAccessBarn("BARN-DL-001", "FARM-DAK-LAK"));
        assertTrue(SUPERVISOR.canAccessBarn("BARN-DL-002", null));
        assertFalse(SUPERVISOR.canAccessBarn("BARN-DL-003", "FARM-DAK-LAK"));
        assertFalse(SUPERVISOR.canAccessBarn(null, "FARM-DAK-LAK"));
    }

    @Test
    void anAccountWithNoRoleSeesNothing() {
        assertFalse(NOBODY.canAccessFarm("FARM-DONG-NAI"));
        assertFalse(NOBODY.canAccessBarn("BARN-DN-001", "FARM-DONG-NAI"));
    }
}
