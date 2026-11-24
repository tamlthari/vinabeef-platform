package com.vinabeef.ingestion.adapter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Turns one MQTT payload from Safetrust into the JSON text of an event.
 * Safetrust sends Base64 of a JSON object. Plain JSON, URL-safe or unpadded Base64, and Base64 with extra '=' at the end
 * are accepted too. Safetrust's own Python client ignores extra '=' (its b64decode does), and some of Safetrust's messages
 * carry two of them; the JDK decoders refuse that when the JSON happens to be a multiple of 3 bytes long, so about one
 * message in three was rejected with "neither a JSON object nor Base64".
 * Anything else is reported as {@link NotAnEventException} with the start and the end of the payload and the reason,
 * so the log says what arrived.
 */
final class PayloadDecoder {

    private static final int PREVIEW_CHARS = 80;
    private static final int TAIL_CHARS = 16;
    private static final int MAX_TRAILING_BYTES = 8;

    private PayloadDecoder() {
    }

    /** The payload is neither a JSON object nor Base64 of one (a broker notice, a JSON array, truncated data...). */
    static final class NotAnEventException extends IllegalArgumentException {
        NotAnEventException(String message) {
            super(message);
        }
    }

    /**
     * @param json   the JSON object text
     * @param repair what had to be tolerated to read the payload (extra padding, stray characters), or null when it was clean
     */
    record Decoded(String json, String repair) {
    }

    /**
     * @param secretToHide text that must not appear in the error message (the API token), may be null
     */
    static String decode(byte[] rawBytes, String secretToHide) {
        return decodeWithNote(rawBytes, secretToHide).json();
    }

    static Decoded decodeWithNote(byte[] rawBytes, String secretToHide) {
        String text = new String(rawBytes, StandardCharsets.UTF_8).trim();
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) {
            throw new NotAnEventException("Empty payload (" + rawBytes.length + " bytes).");
        }

        // 1. Plain JSON object
        if (text.startsWith("{") && text.endsWith("}")) {
            return new Decoded(text, null);
        }

        // 2. A quoted string literal
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }

        // 3. Whitespace and line breaks are not part of the Base64 text; the URL-safe alphabet becomes the standard one
        String sanitized = text.replaceAll("\\s+", "").replace('-', '+').replace('_', '/');

        // 4. Split the text at the first '=': what follows must be padding and nothing else
        int firstPad = sanitized.indexOf('=');
        String body = firstPad < 0 ? sanitized : sanitized.substring(0, firstPad);
        String padding = firstPad < 0 ? "" : sanitized.substring(firstPad);
        if (!padding.chars().allMatch(c -> c == '=')) {
            throw reject(rawBytes, text, secretToHide, "characters follow the '=' padding");
        }

        // 5. Keep the Base64 alphabet only (stray characters were skipped before, too) and count what was dropped
        StringBuilder clean = new StringBuilder(body.length());
        int stray = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (isBase64(c)) {
                clean.append(c);
            } else {
                stray++;
            }
        }
        int remainder = clean.length() % 4;
        if (remainder == 1) {
            throw reject(rawBytes, text, secretToHide,
                    clean.length() + " Base64 characters is not a valid length (the message may be cut off)");
        }

        // 6. Re-pad to what the length needs, whatever the publisher wrote, and decode with the strict decoder
        int wanted = (4 - remainder) % 4;
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(clean + "=".repeat(wanted)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw reject(rawBytes, text, secretToHide, notBase64.getMessage());
        }
        String json = decoded.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new NotAnEventException("Payload decoded, but it is not a JSON object (" + rawBytes.length
                    + " bytes): " + preview(json.isEmpty() ? text : json, secretToHide));
        }

        List<String> repairs = new ArrayList<>();
        if (padding.length() > wanted) {
            repairs.add("ignored " + (padding.length() - wanted) + " extra '=' at the end (" + padding.length()
                    + " found, " + wanted + " belong)");
        }
        if (stray > 0) {
            repairs.add("skipped " + stray + " character" + (stray == 1 ? "" : "s") + " outside the Base64 alphabet");
        }
        return new Decoded(json, repairs.isEmpty() ? null : String.join("; ", repairs));
    }

    private static boolean isBase64(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/';
    }

    /** The rejection: how much arrived, its start, its end, any bytes after the text (CR, LF, NUL: trim() hides them), and why. */
    private static NotAnEventException reject(byte[] raw, String text, String secretToHide, String why) {
        return new NotAnEventException("Payload is neither a JSON object nor Base64 (" + raw.length + " bytes): "
                + preview(text, secretToHide) + " [" + why + "; ends with '" + tail(text, secretToHide)
                + "' followed by " + trailingBytes(raw) + "]");
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

    /** Last characters only, control characters replaced, and the secret removed. */
    static String tail(String text, String secretToHide) {
        String p = text;
        if (secretToHide != null && !secretToHide.isBlank()) {
            p = p.replace(secretToHide, "***");
        }
        p = p.replaceAll("\\p{Cntrl}", "?");
        return p.length() > TAIL_CHARS ? p.substring(p.length() - TAIL_CHARS) : p;
    }

    /** The whitespace and control bytes at the very end of the payload as hex: they are cut off before the text is examined. */
    private static String trailingBytes(byte[] raw) {
        int end = raw.length;
        while (end > 0 && (raw[end - 1] & 0xff) <= 0x20) {
            end--;
        }
        if (end == raw.length) {
            return "no extra bytes";
        }
        StringBuilder sb = new StringBuilder("bytes");
        for (int i = end; i < Math.min(raw.length, end + MAX_TRAILING_BYTES); i++) {
            sb.append(String.format(" %02x", raw[i] & 0xff));
        }
        return sb.toString();
    }
}
