package com.vinabeef.ingestion.adapter;

import com.vinabeef.ingestion.adapter.PayloadDecoder.NotAnEventException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(e.getMessage().length() < 320);
        assertTrue(PayloadDecoder.preview("a\u0000b", null).equals("a?b"));
    }

    // ---- Safetrust's own messages: Base64 with two extra '=' at the end ---------------------------------------------
    // Seen on Web/475/Monitoring/<device>: every rejected payload in the logs was 2 more than a multiple of 4 characters long.

    private static String json(int bytes) {
        StringBuilder sb = new StringBuilder("{\"EVENT_KEY\":705362,\"NOTE\":\"");
        while (sb.length() < bytes - 2) {
            sb.append('x');
        }
        return sb.append("\"}").toString();
    }

    @Test
    void twoExtraEqualsSignsAreIgnoredWhateverTheLengthOfTheJson() {
        for (int n = 399; n <= 401; n++) {                       // all three remainders of n mod 3: natural padding none, '==' and '='
            String json = json(n);
            String padded = Base64.getEncoder().encodeToString(bytes(json));
            PayloadDecoder.Decoded d = PayloadDecoder.decodeWithNote(bytes(padded + "=="), "SECRET-TOKEN");
            assertEquals(json, d.json(), "JSON of " + n + " bytes");
            assertTrue(d.repair().contains("extra '='"), d.repair());
        }
    }

    @Test
    void theShapeFromTheLogsIsAcceptedAndItsLengthIsTwoMoreThanAMultipleOfFour() {
        String json = json(399);                                  // a multiple of 3 bytes: no natural padding, so '==' stood in an illegal place
        String payload = Base64.getEncoder().encodeToString(bytes(json)) + "==";
        assertEquals(2, payload.length() % 4);
        assertEquals(json, decode(payload));
    }

    @Test
    void aCleanPayloadHasNoRepairNote() {
        PayloadDecoder.Decoded d = PayloadDecoder.decodeWithNote(bytes(Base64.getEncoder().encodeToString(bytes(EVENT))), null);
        assertEquals(EVENT, d.json());
        assertNull(d.repair());
        assertNull(PayloadDecoder.decodeWithNote(bytes(EVENT), null).repair());
        String unpadded = Base64.getEncoder().withoutPadding().encodeToString(bytes(EVENT));
        assertNull(PayloadDecoder.decodeWithNote(bytes(unpadded), null).repair(), "missing padding is a normal variant");
    }

    @Test
    void strayCharactersInsideTheBase64AreSkippedAndReported() {
        String padded = Base64.getEncoder().encodeToString(bytes(EVENT));
        PayloadDecoder.Decoded d = PayloadDecoder.decodeWithNote(bytes(padded.substring(0, 8) + "!" + padded.substring(8)), null);
        assertEquals(EVENT, d.json());
        assertTrue(d.repair().contains("skipped 1 character"), d.repair());
    }

    @Test
    void textAfterThePaddingIsStillRejectedAndTheMessageSaysWhy() {
        String padded = Base64.getEncoder().encodeToString(bytes(EVENT));         // "==" at the end for this event
        NotAnEventException e = assertThrows(NotAnEventException.class, () -> decode(padded + "AB"));
        assertTrue(e.getMessage().startsWith("Payload is neither a JSON object nor Base64 ("), e.getMessage());
        assertTrue(e.getMessage().contains("characters follow the '=' padding"), e.getMessage());
        assertTrue(e.getMessage().contains("ends with '") && e.getMessage().contains("AB'"), e.getMessage());
        assertTrue(e.getMessage().endsWith("followed by no extra bytes]"), e.getMessage());
    }

    @Test
    void aCutOffBase64TextIsReportedAsCutOff() {
        String padded = Base64.getEncoder().withoutPadding().encodeToString(bytes(EVENT));
        String cut = padded.substring(0, padded.length() - (padded.length() % 4 == 2 ? 1 : 2));   // leaves 4n+1 characters
        assertEquals(1, cut.length() % 4);
        NotAnEventException e = assertThrows(NotAnEventException.class, () -> decode(cut));
        assertTrue(e.getMessage().contains("may be cut off"), e.getMessage());
    }

    @Test
    void theEndOfARejectedPayloadShowsTrailingWhitespaceAndNulsAsBytesAndNeverTheToken() {
        NotAnEventException e = assertThrows(NotAnEventException.class, () -> decode("status=OK SECRET-TOKEN\r\n\0"));
        assertFalse(e.getMessage().contains("SECRET-TOKEN"), e.getMessage());
        assertTrue(e.getMessage().endsWith("followed by bytes 0d 0a 00]"), e.getMessage());
        assertFalse(e.getMessage().contains("TOKEN"), e.getMessage());
    }
}
