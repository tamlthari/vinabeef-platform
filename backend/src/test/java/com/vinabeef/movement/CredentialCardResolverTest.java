package com.vinabeef.movement;

import com.vinabeef.movement.CredentialCardResolver.Resolution;
import com.vinabeef.movement.port.CredentialLookup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Credential number to card UID: Safetrust's own pairs first, the tag register second, and never a guess. */
class CredentialCardResolverTest {

    /** Answers from two lists and remembers what it was asked. */
    private static final class Lookup implements CredentialLookup {
        final List<String> calls = new ArrayList<>();
        List<String> history = List.of();
        List<String> register = List.of();

        @Override
        public List<String> cardsByHistory(String credential) {
            calls.add("HISTORY:" + credential);
            return history;
        }

        @Override
        public List<String> cardsByRegister(String credential) {
            calls.add("REGISTER:" + credential);
            return register;
        }
    }

    @Test
    void aNumberSeenWithExactlyOneCardInSafetrustEventsIsThatCard() {
        Lookup lookup = new Lookup();
        lookup.history = List.of("044e6602ad1490 ");
        Optional<Resolution> r = new CredentialCardResolver(lookup).resolve(" 38 ");
        assertEquals(Optional.of(new Resolution("044E6602AD1490", CredentialCardResolver.BY_HISTORY)), r);
        assertEquals(List.of("HISTORY:38"), lookup.calls);        // blanks are trimmed before the lookup
    }

    @Test
    void aNumberMovedToAnotherCardIsNotGuessedAndTheRegisterIsNotAskedEither() {
        Lookup lookup = new Lookup();
        lookup.history = List.of("04AAAA", "04BBBB");
        assertEquals(Optional.empty(), new CredentialCardResolver(lookup).resolve("38"));
        assertEquals(List.of("HISTORY:38"), lookup.calls);
    }

    @Test
    void aNumberUnknownToSafetrustEventsFallsBackToTheTagRegister() {
        Lookup lookup = new Lookup();
        lookup.register = List.of("e280116060000204");
        Optional<Resolution> r = new CredentialCardResolver(lookup).resolve("1005");
        assertEquals(Optional.of(new Resolution("E280116060000204", CredentialCardResolver.BY_REGISTER)), r);
        assertEquals(List.of("HISTORY:1005", "REGISTER:1005"), lookup.calls);
    }

    @Test
    void aNumberOnTwoRegisteredTagsOrOnNoneStaysUnresolved() {
        Lookup two = new Lookup();
        two.register = List.of("04AAAA", "04BBBB");
        assertEquals(Optional.empty(), new CredentialCardResolver(two).resolve("1005"));
        Lookup none = new Lookup();
        assertEquals(Optional.empty(), new CredentialCardResolver(none).resolve("1005"));
        assertEquals(List.of("HISTORY:1005", "REGISTER:1005"), none.calls);
    }

    @Test
    void aMissingOrBlankNumberAsksNothing() {
        Lookup lookup = new Lookup();
        assertEquals(Optional.empty(), new CredentialCardResolver(lookup).resolve(null));
        assertEquals(Optional.empty(), new CredentialCardResolver(lookup).resolve("   "));
        assertTrue(lookup.calls.isEmpty());
    }
}
