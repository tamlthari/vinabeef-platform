package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListFiltersTest {

    @Test
    void oneOfAcceptsOnlyTheFixedWordsAndReturnsTheirSpelling() {
        assertEquals("UNDER_TREATMENT", ListFilters.oneOf(" under_treatment ", ListQueries.HEALTH));
        assertNull(ListFilters.oneOf("", ListQueries.HEALTH));
        assertNull(ListFilters.oneOf(null, ListQueries.HEALTH));
        assertNull(ListFilters.oneOf("HEALTHY'; DROP TABLE animals;--", ListQueries.HEALTH));
    }

    @Test
    void manyOfKeepsKnownWordsOnceInOrder() {
        assertEquals(List.of("QUARANTINE", "UNDER_TREATMENT"),
                ListFilters.manyOf("quarantine, bogus, UNDER_TREATMENT,quarantine", ListQueries.HEALTH));
        assertTrue(ListFilters.manyOf(null, ListQueries.HEALTH).isEmpty());
        assertTrue(ListFilters.manyOf(" , ,", ListQueries.HEALTH).isEmpty());
    }

    @Test
    void idsAreTrimmedDedupedAndCapped() {
        assertEquals(List.of("BARN-1", "BARN-2"), ListFilters.ids(" BARN-1, ,BARN-2,BARN-1"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 200; i++) many.append("B").append(i).append(',');
        assertEquals(ListFilters.MAX_IDS, ListFilters.ids(many.toString()).size());
    }

    @Test
    void marksMakesOnePlaceholderPerValue() {
        assertEquals("?, ?, ?", ListFilters.marks(3));
    }
}
