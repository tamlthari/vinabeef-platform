package com.vinabeef.ingestion.adapter;

import com.vinabeef.ingestion.adapter.PayloadDecoder.NotAnEventException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PayloadDecoderTest {

    private static final String EVENT = "{\"EVENT_KEY\":580307087,\"READER_NAME\":\"DrC0F8\"}";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String decode(String payload) {
        return PayloadDecoder.decode(bytes(payload), "SECRET-TOKEN");
    }

    @Test
    void base64OfAJsonObjectIsDecoded() {
        assertEquals(EVENT, decode(Base64.getEncoder().encodeToString(bytes(EVENT))));
    }

    @Test
    void plainJsonIsAcceptedAsIs() {
        assertEquals(EVENT, decode("  " + EVENT + "\n"));
    }

    @Test
    void urlSafeUnpaddedAndWrappedBase64IsAccepted() {
        String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(EVENT));
        assertEquals(EVENT, decode(urlSafe));
        String wrapped = Base64.getMimeEncoder(16, "\r\n".getBytes()).encodeToString(bytes(EVENT));
        assertEquals(EVENT, decode("\"" + wrapped + "\""));
    }

    @Test
    void aByteOrderMarkInFrontOfJsonIsIgnored() {
        assertEquals(EVENT, decode("﻿" + EVENT));
    }

    @Test
    void textWithAnEqualsSignIsReportedInsteadOfAFailingBase64Decode() {
        NotAnEventException e = assertThrows(NotAnEventException.class, () -> decode("status=OK"));
        assertTrue(e.getMessage().contains("status=OK"));
        assertFalse(e.getMessage().contains("4-byte"));
    }

    @Test
    void twoBase64BlobsGluedTogetherAreReported() {
        String glued = Base64.getEncoder().encodeToString(bytes(EVENT)) + Base64.getEncoder().encodeToString(bytes(EVENT));
        assertThrows(NotAnEventException.class, () -> decode(glued));
    }

    @Test
    void aJsonArrayIsReportedWithAPreview() {
        NotAnEventException e = assertThrows(NotAnEventException.class, () -> decode("[{\"A\":1}]"));
        assertTrue(e.getMessage().contains("[{\"A\":1}]"));
    }

    @Test
    void base64OfSomethingThatIsNotJsonIsReported() {
        String notJson = Base64.getEncoder().encodeToString(bytes("hello world"));
        NotAnEventException e = assertThrows(NotAnEventException.class, () -> decode(notJson));
        assertTrue(e.getMessage().contains("hello world"));
    }

    @Test
    void anEmptyPayloadIsReported() {
        assertThrows(NotAnEventException.class, () -> decode("   "));
    }

    @Test
    void theTokenNeverAppearsInTheMessageAndLongTextIsCut() {
        NotAnEventException e = assertThrows(NotAnEventException.class, () -> decode("note=SECRET-TOKEN " + "x".repeat(300)));
        assertFalse(e.getMessage().contains("SECRET-TOKEN"));
        assertTrue(e.getMessage().length() < 200);
        assertTrue(PayloadDecoder.preview("a\u0000b", null).equals("a?b"));
    }
}
