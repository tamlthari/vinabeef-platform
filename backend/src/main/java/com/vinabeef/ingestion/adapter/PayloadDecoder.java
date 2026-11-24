package com.vinabeef.ingestion.adapter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Turns one MQTT payload from Safetrust into the JSON text of an event.
 * Safetrust sends Base64 of a JSON object. Plain JSON and URL-safe or unpadded Base64 are accepted too.
 * Anything else is reported as {@link NotAnEventException} with a short preview, so the log says what arrived.
 */
final class PayloadDecoder {

    private static final int PREVIEW_CHARS = 80;

    private PayloadDecoder() {
    }

    /** The payload is neither a JSON object nor Base64 of one (a broker notice, a JSON array, truncated data...). */
    static final class NotAnEventException extends IllegalArgumentException {
        NotAnEventException(String message) {
            super(message);
        }
    }

    /**
     * @param secretToHide text that must not appear in the error message (the API token), may be null
     */
    static String decode(byte[] rawBytes, String secretToHide) {
        String text = new String(rawBytes, StandardCharsets.UTF_8).trim();
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) {
            throw new NotAnEventException("Empty payload (" + rawBytes.length + " bytes).");
        }

        // 1. Plain JSON object
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }

        // 2. A quoted string literal
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }

        // 3. Whitespace and line breaks are not part of the Base64 text
        String sanitized = text.replaceAll("\\s+", "");

        // 4. URL-safe alphabet to the standard one
        sanitized = sanitized.replace('-', '+').replace('_', '/');

        // 5. Restore '=' padding when the publisher left it out
        int remainder = sanitized.length() % 4;
        if (remainder == 2) {
            sanitized += "==";
        } else if (remainder == 3) {
            sanitized += "=";
        }

        // 6. Decode. The MIME decoder skips stray characters, which is why the result is checked below.
        String decoded;
        try {
            decoded = new String(
                    Base64.getMimeDecoder().decode(sanitized.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw new NotAnEventException("Payload is neither a JSON object nor Base64 (" + rawBytes.length
                    + " bytes): " + preview(text, secretToHide));
        }
        String json = decoded.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new NotAnEventException("Payload decoded, but it is not a JSON object (" + rawBytes.length
                    + " bytes): " + preview(json.isEmpty() ? text : json, secretToHide));
        }
        return json;
    }

    /** First characters only, control characters replaced, and the secret removed. */
    static String preview(String text, String secretToHide) {
        String p = text;
        if (secretToHide != null && !secretToHide.isBlank()) {
            p = p.replace(secretToHide, "***");
        }
        p = p.replaceAll("\\p{Cntrl}", "?");
        return p.length() > PREVIEW_CHARS ? p.substring(0, PREVIEW_CHARS) + "..." : p;
    }
}
