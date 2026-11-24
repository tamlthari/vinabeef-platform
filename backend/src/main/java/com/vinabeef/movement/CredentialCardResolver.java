package com.vinabeef.movement;

import com.vinabeef.movement.port.CredentialLookup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Turns the credential number typed by a USB reader (for example {@code 38}) into the card UID the rest of the system uses
 * (for example {@code 044E6602AD1490}). Nothing is guessed: a number that maps to no card, or to two, stays unresolved.
 *
 * <ol>
 *   <li>{@link #BY_HISTORY}: Safetrust's own events carry both numbers ({@code CREDENTIAL_IDENTIFIER} and {@code CARD_UUID}),
 *       so a number seen with exactly one card in its latest reads is known for certain. Tapping a new tag once at a Safetrust
 *       reader is enough to teach the system its number.</li>
 *   <li>{@link #BY_REGISTER}: the number entered on the tag in the cattle register ({@code rfid_tags.credential_identifier}).
 *       Used only when the history knows nothing, because that column also holds placeholder values.</li>
 * </ol>
 */
@Component
@Slf4j
public class CredentialCardResolver {

    public static final String BY_HISTORY = "SAFETRUST_HISTORY";
    public static final String BY_REGISTER = "TAG_REGISTER";

    /** The card and how it was found. */
    public record Resolution(String cardUuid, String resolvedBy) {
    }

    private final CredentialLookup lookup;

    public CredentialCardResolver(CredentialLookup lookup) {
        this.lookup = lookup;
    }

    public Optional<Resolution> resolve(String credential) {
        if (credential == null || credential.isBlank()) {
            return Optional.empty();
        }
        String number = credential.trim();

        List<String> seen = lookup.cardsByHistory(number);
        if (seen.size() == 1) {
            return Optional.of(new Resolution(seen.get(0).trim().toUpperCase(), BY_HISTORY));
        }
        if (seen.size() > 1) {
            log.warn("Credential {} was seen on {} different cards in Safetrust events: not resolved.", number, seen.size());
            return Optional.empty();
        }

        List<String> tags = lookup.cardsByRegister(number);
        if (tags.size() == 1) {
            return Optional.of(new Resolution(tags.get(0).trim().toUpperCase(), BY_REGISTER));
        }
        if (tags.size() > 1) {
            log.warn("Credential {} is registered on {} active tags: not resolved.", number, tags.size());
        }
        return Optional.empty();
    }
}
