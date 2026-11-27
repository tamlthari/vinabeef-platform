package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarnLookupTest {

    private static Map<String, Object> barn(String id, String code, String farm) {
        return Map.of("barn_id", id, "code", code, "farm_name", farm);
    }

    private static final List<Map<String, Object>> ONE_FARM = List.of(
            barn("BARN-DL-011", "DL-BARN-011", "Dak Lak"), barn("BARN-DL-012", "DL-BARN-012", "Dak Lak"));
    private static final List<Map<String, Object>> TWO_FARMS = List.of(
            barn("BARN-DL-01", "BARN-01", "Dak Lak"), barn("BARN-GL-01", "BARN-01", "Gia Lai"), barn("BARN-GL-02", "BARN-02", "Gia Lai"));

    @Test
    void theCodeOnTheWebScreenFindsTheBarn() {
        BarnLookup.Result r = BarnLookup.resolve("DL-BARN-011", ONE_FARM);
        assertEquals(BarnLookup.Kind.FOUND, r.kind());
        assertEquals("BARN-DL-011", r.barnId());
        assertEquals("DL-BARN-011", r.label());                  // the phone shows the code, never the id
    }

    @Test
    void theOldIdStillWorksSoAPhoneThatSavedOneKeepsWorking() {
        BarnLookup.Result r = BarnLookup.resolve("BARN-DL-012", ONE_FARM);
        assertEquals("BARN-DL-012", r.barnId());
        assertEquals("DL-BARN-012", r.label());
        assertEquals("BARN-DL-012", BarnLookup.resolve("barn-dl-012", ONE_FARM).barnId());
    }

    @Test
    void typingIsForgiving() {
        assertEquals("BARN-DL-011", BarnLookup.resolve("  dl-barn-011 ", ONE_FARM).barnId());
    }

    @Test
    void emptyMeansMyBarnAndAllMeansEveryBarn() {
        assertEquals("BARN-DL-011", BarnLookup.resolve("", ONE_FARM).barnId());
        assertEquals("BARN-DL-011", BarnLookup.resolve(null, ONE_FARM).barnId());
        assertEquals(BarnLookup.Kind.ALL, BarnLookup.resolve("all", ONE_FARM).kind());
        assertEquals(BarnLookup.Kind.ALL, BarnLookup.resolve("ALL", List.of()).kind());
    }

    @Test
    void aBarnThatIsNotInTheListIsNotFound() {
        assertEquals(BarnLookup.Kind.NONE, BarnLookup.resolve("DL-BARN-999", ONE_FARM).kind());
        assertEquals(BarnLookup.Kind.NONE, BarnLookup.resolve("", List.of()).kind());
        assertEquals(BarnLookup.Kind.NONE, BarnLookup.resolve("DL-BARN-011", List.of()).kind());
    }

    @Test
    void aCodeUsedByTwoFacilitiesIsNotGuessed() {
        BarnLookup.Result r = BarnLookup.resolve("BARN-01", TWO_FARMS);
        assertEquals(BarnLookup.Kind.AMBIGUOUS, r.kind());
        assertTrue(r.message().contains("BARN-01 - Dak Lak"), r.message());
        assertEquals("", r.barnId());
    }

    @Test
    void theCodeWithItsFacilityNamesOneBarn() {
        BarnLookup.Result r = BarnLookup.resolve("barn-01 - gia lai", TWO_FARMS);
        assertEquals("BARN-GL-01", r.barnId());
        assertEquals("BARN-01 - Gia Lai", r.label());            // shown the same way it is typed
    }

    @Test
    void aCodeThatIsUniqueInTheListIsShownAlone() {
        assertEquals("BARN-02", BarnLookup.resolve("BARN-02", TWO_FARMS).label());
        assertEquals("BARN-GL-02", BarnLookup.resolve("BARN-02", TWO_FARMS).barnId());
    }

    @Test
    void anIdBeatsACodeThatLooksTheSame() {
        List<Map<String, Object>> odd = List.of(barn("BARN-X", "A-1", "F"), barn("B-2", "BARN-X", "F"));
        assertEquals("BARN-X", BarnLookup.resolve("BARN-X", odd).barnId());
    }

    @Test
    void emptyMeansTheFirstBarnWithItsLabel() {
        assertEquals("BARN-01 - Dak Lak", BarnLookup.resolve("", TWO_FARMS).label());
    }
}
