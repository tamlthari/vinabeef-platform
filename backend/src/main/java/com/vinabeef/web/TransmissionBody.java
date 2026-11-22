package com.vinabeef.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * What the transmission log keeps of a report body: the text (cut at a cap), its exact size and the SHA-256 of the
 * <b>complete</b> body. The hash and size describe the bytes the gateway sends (UTF-8), so the test server's own record
 * of what it received can be compared with them even when the stored text had to be cut.
 */
final class TransmissionBody {

    /** {@code text} is at most the cap; {@code bytes} and {@code sha256} always describe the whole body. */
    record Captured(String text, long bytes, String sha256, boolean truncated) {
    }

    private TransmissionBody() {
    }

    static Captured capture(String payload, int maxChars) {
        String body = payload == null ? "" : payload;
        byte[] utf8 = body.getBytes(StandardCharsets.UTF_8);
        int cap = Math.max(0, maxChars);
        boolean truncated = body.length() > cap;
        return new Captured(truncated ? cut(body, cap) : body, utf8.length, sha256(utf8), truncated);
    }

    /** First {@code max} characters, never ending in the middle of a surrogate pair (which would corrupt the character). */
    static String cut(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int end = Math.max(0, max);
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    static String contentType(String format) {
        return "XML".equalsIgnoreCase(format) ? "application/xml" : "application/json";
    }

    static String fileName(String transmissionId, String format) {
        String safe = transmissionId == null ? "transmission" : transmissionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe + ("XML".equalsIgnoreCase(format) ? ".xml" : ".json");
    }
}
