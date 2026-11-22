package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SortSpecTest {

    private static final Map<String, String> ALLOWED = Map.of(
            "created", "a.created_at",
            "id", "a.animal_id",
            "barn", "b.code");

    @Test
    void noSortMeansTheDefaultNewestFirst() {
        assertEquals("a.created_at DESC NULLS LAST, a.animal_id ASC",
                SortSpec.orderBy(null, null, ALLOWED, "created", "desc", "a.animal_id"));
    }

    @Test
    void aKnownKeyUsesTheRequestedDirectionAndAStableTieBreaker() {
        assertEquals("b.code DESC NULLS LAST, a.animal_id ASC",
                SortSpec.orderBy("barn", "desc", ALLOWED, "created", "desc", "a.animal_id"));
    }

    @Test
    void aNewKeyWithoutADirectionStartsAscending() {
        assertEquals("b.code ASC NULLS LAST, a.animal_id ASC",
                SortSpec.orderBy("barn", null, ALLOWED, "created", "desc", "a.animal_id"));
    }

    @Test
    void sortingByTheTieBreakerItselfDoesNotRepeatIt() {
        assertEquals("a.animal_id DESC NULLS LAST",
                SortSpec.orderBy("id", "desc", ALLOWED, "created", "desc", "a.animal_id"));
    }

    @Test
    void textFromTheRequestNeverReachesTheSql() {
        String sql = SortSpec.orderBy("id; DROP TABLE animals", "desc; --", ALLOWED, "created", "desc", "a.animal_id");

        assertFalse(sql.contains("DROP"));
        assertEquals("a.created_at DESC NULLS LAST, a.animal_id ASC", sql);
    }

    @Test
    void keysAreCaseInsensitiveAndTheEffectiveValuesMatchWhatIsUsed() {
        assertEquals("barn", SortSpec.effectiveKey("BARN", ALLOWED, "created"));
        assertEquals("created", SortSpec.effectiveKey("nope", ALLOWED, "created"));
        assertEquals("desc", SortSpec.effectiveDir(null, null, ALLOWED, "created", "desc"));
        assertEquals("asc", SortSpec.effectiveDir("barn", null, ALLOWED, "created", "desc"));
        assertEquals("desc", SortSpec.effectiveDir("barn", "DESC", ALLOWED, "created", "desc"));
    }
}
