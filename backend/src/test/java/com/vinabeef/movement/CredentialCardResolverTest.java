package com.vinabeef.movement;

import com.vinabeef.movement.CredentialCardResolver.Resolution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Credential number to card UID: Safetrust's own pairs first, the tag register second, and never a guess. */
class CredentialCardResolverTest {

    @Test
    void aNumberSeenWithExactlyOneCardInSafetrustEventsIsThatCard() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("044e6602ad1490 ");
        Optional<Resolution> r = new CredentialCardResolver(jdbc).resolve(" 38 ");
        assertEquals(Optional.of(new Resolution("044E6602AD1490", CredentialCardResolver.BY_HISTORY)), r);
        assertEquals(List.of("HISTORY"), jdbc.calls);
        assertEquals("38", jdbc.lastArgs.get("HISTORY")[0]);        // blanks are trimmed before the lookup
    }

    @Test
    void aNumberMovedToAnotherCardIsNotGuessedAndTheRegisterIsNotAskedEither() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("04AAAA", "04BBBB");
        assertEquals(Optional.empty(), new CredentialCardResolver(jdbc).resolve("38"));
        assertEquals(List.of("HISTORY"), jdbc.calls);
    }

    @Test
    void aNumberUnknownToSafetrustEventsFallsBackToTheTagRegister() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList().answerList("e280116060000204");
        Optional<Resolution> r = new CredentialCardResolver(jdbc).resolve("1005");
        assertEquals(Optional.of(new Resolution("E280116060000204", CredentialCardResolver.BY_REGISTER)), r);
        assertEquals(List.of("HISTORY", "REGISTER"), jdbc.calls);
    }

    @Test
    void aNumberOnTwoRegisteredTagsOrOnNoneStaysUnresolved() {
        ScriptedJdbc two = new ScriptedJdbc().answerList().answerList("04AAAA", "04BBBB");
        assertEquals(Optional.empty(), new CredentialCardResolver(two).resolve("1005"));
        ScriptedJdbc none = new ScriptedJdbc();
        assertEquals(Optional.empty(), new CredentialCardResolver(none).resolve("1005"));
        assertEquals(List.of("HISTORY", "REGISTER"), none.calls);
    }

    @Test
    void aMissingOrBlankNumberAsksNothing() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        assertEquals(Optional.empty(), new CredentialCardResolver(jdbc).resolve(null));
        assertEquals(Optional.empty(), new CredentialCardResolver(jdbc).resolve("   "));
        assertTrue(jdbc.calls.isEmpty());
    }
}
