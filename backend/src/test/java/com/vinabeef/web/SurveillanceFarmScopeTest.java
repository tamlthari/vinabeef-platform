package com.vinabeef.web;

import com.vinabeef.security.UserScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurveillanceFarmScopeTest {

    private static UserScope manager(String farm) {
        return new UserScope("manager", "FARM_MANAGER", farm, List.of());
    }

    @Test
    void globalAccountsMayAskForOneFarmOrAll() {
        UserScope admin = new UserScope("admin", "SYSTEM_ADMIN", "ALL", List.of());
        UserScope exec = new UserScope("exec", "EXECUTIVE", null, List.of());
        assertEquals("ALL", SurveillanceFarmScope.resolve(admin, null));
        assertEquals("ALL", SurveillanceFarmScope.resolve(admin, "  "));
        assertEquals("FARM-GIA-LAI", SurveillanceFarmScope.resolve(exec, " FARM-GIA-LAI "));
    }

    @Test
    void aFarmManagerAlwaysGetsTheOwnFarm() {
        UserScope m = manager("FARM-DAK-LAK");
        assertEquals("FARM-DAK-LAK", SurveillanceFarmScope.resolve(m, null));
        assertEquals("FARM-DAK-LAK", SurveillanceFarmScope.resolve(m, "ALL"));
        assertEquals("FARM-DAK-LAK", SurveillanceFarmScope.resolve(m, "all"));
        assertEquals("FARM-DAK-LAK", SurveillanceFarmScope.resolve(m, "FARM-DAK-LAK"));
    }

    @Test
    void aFarmManagerAskingForAnotherFarmIsRefusedNotAnsweredWithTheOwn() {
        var e = assertThrows(SurveillanceFarmScope.Denied.class,
                () -> SurveillanceFarmScope.resolve(manager("FARM-DAK-LAK"), "FARM-GIA-LAI"));
        assertTrue(e.getMessage().contains("FARM-GIA-LAI"));
    }

    @Test
    void aFarmManagerWithoutAFarmIsRefusedInsteadOfSeeingEveryFarm() {
        for (String none : new String[]{null, "", "  ", "ALL", "all"}) {
            assertThrows(SurveillanceFarmScope.Denied.class,
                    () -> SurveillanceFarmScope.resolve(manager(none), "ALL"), "farm=" + none);
            assertThrows(SurveillanceFarmScope.Denied.class,
                    () -> SurveillanceFarmScope.resolve(manager(none), "FARM-DAK-LAK"), "farm=" + none);
        }
    }

    @Test
    void otherRolesAreRefused() {
        assertThrows(SurveillanceFarmScope.Denied.class,
                () -> SurveillanceFarmScope.resolve(new UserScope("sup", "BARN_SUPERVISOR", "FARM-DAK-LAK", List.of("B1")), "ALL"));
        assertThrows(SurveillanceFarmScope.Denied.class,
                () -> SurveillanceFarmScope.resolve(new UserScope("x", "SOMETHING_ELSE", null, List.of()), "ALL"));
    }
}
