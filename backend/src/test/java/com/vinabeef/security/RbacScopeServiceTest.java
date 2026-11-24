package com.vinabeef.security;

import com.vinabeef.security.port.AuthenticatedUser;
import com.vinabeef.security.port.ScopeDirectory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The signed-in person's scope, and whether they may act on a barn: the rules, with the directory standing in. */
class RbacScopeServiceTest {

    private static final class Directory implements ScopeDirectory {
        final Map<String, Account> accounts = new HashMap<>();
        final Map<String, List<String>> assignments = new HashMap<>();
        final Map<String, String> barnFarm = new HashMap<>();
        int barnLookups;

        @Override
        public Optional<Account> findAccount(String username) {
            return Optional.ofNullable(accounts.get(username));
        }

        @Override
        public List<String> barnsAssignedTo(String username) {
            return assignments.getOrDefault(username, List.of());
        }

        @Override
        public Optional<String> farmOfBarn(String barnId) {
            barnLookups++;
            return Optional.ofNullable(barnFarm.get(barnId));
        }
    }

    private final Directory directory = new Directory();
    private String signedIn;                                        // null: nobody

    private RbacScopeService service() {
        AuthenticatedUser who = () -> Optional.ofNullable(signedIn);
        return new RbacScopeService(who, directory);
    }

    @Test
    void nobodySignedInSeesNothing() {
        UserScope scope = service().getCurrentScope();
        assertEquals("anonymous", scope.username());
        assertEquals("NONE", scope.role());
        assertTrue(scope.barnIds().isEmpty());
        assertFalse(scope.isGlobal());
    }

    @Test
    void aSignedInPersonWithNoAccountSeesNothing() {
        signedIn = "ghost";
        UserScope scope = service().getCurrentScope();
        assertEquals("ghost", scope.username());
        assertEquals("NONE", scope.role());
    }

    @Test
    void aBarnSupervisorGetsTheirAssignedBarns() {
        directory.accounts.put("sup", new ScopeDirectory.Account("sup", "BARN_SUPERVISOR", "FARM-1"));
        directory.assignments.put("sup", List.of("BARN-1", "BARN-3"));
        signedIn = "sup";
        UserScope scope = service().getCurrentScope();
        assertEquals("BARN_SUPERVISOR", scope.role());
        assertEquals("FARM-1", scope.farmId());
        assertEquals(List.of("BARN-1", "BARN-3"), scope.barnIds());
    }

    @Test
    void otherRolesAreNotGivenBarnsEvenIfRowsExist() {
        directory.accounts.put("mgr", new ScopeDirectory.Account("mgr", "FARM_MANAGER", "FARM-1"));
        directory.assignments.put("mgr", List.of("BARN-9"));
        signedIn = "mgr";
        assertTrue(service().getCurrentScope().barnIds().isEmpty());
    }

    @Test
    void anAccountWithNoFarmHasANullFarm() {
        directory.accounts.put("root", new ScopeDirectory.Account("root", "SYSTEM_ADMIN", null));
        signedIn = "root";
        assertNull(service().getCurrentScope().farmId());
    }

    @Test
    void globalRolesMayActOnAnyBarnWithoutAskingTheDirectory() {
        UserScope admin = new UserScope("root", "SYSTEM_ADMIN", null, List.of());
        assertTrue(service().canAccessBarnId(admin, "BARN-1"));
        assertTrue(service().canAccessBarnId(admin, null));
        assertEquals(0, directory.barnLookups);
    }

    @Test
    void aBarnSupervisorMayActOnlyOnAssignedBarns() {
        UserScope sup = new UserScope("sup", "BARN_SUPERVISOR", "FARM-1", List.of("BARN-1"));
        assertTrue(service().canAccessBarnId(sup, "BARN-1"));
        assertFalse(service().canAccessBarnId(sup, "BARN-2"));
        assertEquals(0, directory.barnLookups, "the supervisor's list decides; no farm lookup");
    }

    @Test
    void aFarmManagerMayActOnBarnsOfTheirOwnFarm() {
        directory.barnFarm.put("BARN-1", "FARM-1");
        directory.barnFarm.put("BARN-7", "FARM-2");
        UserScope mgr = new UserScope("mgr", "FARM_MANAGER", "FARM-1", List.of());
        assertTrue(service().canAccessBarnId(mgr, "BARN-1"));
        assertFalse(service().canAccessBarnId(mgr, "BARN-7"));
        assertFalse(service().canAccessBarnId(mgr, "BARN-404"), "a barn that does not exist is nobody's");
    }

    @Test
    void aBlankOrMissingBarnIdIsNotAccessibleToAnyoneButGlobalRoles() {
        UserScope mgr = new UserScope("mgr", "FARM_MANAGER", "FARM-1", List.of());
        assertFalse(service().canAccessBarnId(mgr, null));
        assertFalse(service().canAccessBarnId(mgr, "  "));
    }

    @Test
    void aPersonWithNoRoleMayNotActOnAnyBarn() {
        UserScope none = new UserScope("anonymous", "NONE", null, List.of());
        assertFalse(service().canAccessBarnId(none, "BARN-1"));
    }
}
