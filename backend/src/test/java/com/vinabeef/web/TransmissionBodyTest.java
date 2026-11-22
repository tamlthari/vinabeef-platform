package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the transmission log keeps of a report body. */
class TransmissionBodyTest {

    @Test
    void aBodyUnderTheCapIsKeptWholeWithItsSizeAndHash() {
        var c = TransmissionBody.capture("{\"a\":1}", 100);

        assertEquals("{\"a\":1}", c.text());
        assertEquals(7L, c.bytes());
        assertFalse(c.truncated());
        assertEquals(64, c.sha256().length());
        assertEquals(TransmissionBody.sha256("{\"a\":1}".getBytes(StandardCharsets.UTF_8)), c.sha256());
    }

    @Test
    void theKnownHashOfAnEmptyBodyIsRight() {
        // the SHA-256 of zero bytes is a published constant
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", TransmissionBody.capture("", 10).sha256());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", TransmissionBody.capture(null, 10).sha256());
    }

    @Test
    void thePublishedSha256TestVectorForAbcIsReproduced() {
        // FIPS 180-2 example: SHA-256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                TransmissionBody.capture("abc", 10).sha256());
    }

    @Test
    void aLongBodyIsCutButSizeAndHashStillDescribeTheWholeBody() {
        String body = "x".repeat(1000);
        var c = TransmissionBody.capture(body, 100);

        assertEquals(100, c.text().length());
        assertTrue(c.truncated());
        assertEquals(1000L, c.bytes());
        assertEquals(TransmissionBody.sha256(body.getBytes(StandardCharsets.UTF_8)), c.sha256());
    }

    @Test
    void sizeIsCountedInBytesNotCharacters() {
        // "đ" is 2 bytes, the cow emoji is 4 bytes (and 2 chars)
        var c = TransmissionBody.capture("đ🐄", 100);
        assertEquals(6L, c.bytes());
        assertEquals(3, c.text().length());
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        String s = "ab🐄cd";                  // chars: a b \uD83D \uDC04 c d
        assertEquals("ab", TransmissionBody.cut(s, 3));       // 3 would end on the high half of the emoji
        assertEquals("ab🐄", TransmissionBody.cut(s, 4));
        assertEquals("", TransmissionBody.cut("🐄", 1));
        assertEquals("abcdef", TransmissionBody.cut("abcdef", 10));
        assertEquals("", TransmissionBody.cut("abc", 0));
        assertEquals("", TransmissionBody.cut("abc", -5));
    }

    @Test
    void contentTypeAndFileNameFollowTheFormat() {
        assertEquals("application/xml", TransmissionBody.contentType("XML"));
        assertEquals("application/xml", TransmissionBody.contentType("xml"));
        assertEquals("application/json", TransmissionBody.contentType("JSON"));
        assertEquals("application/json", TransmissionBody.contentType(null));
        assertEquals("TX-AB12CD34.xml", TransmissionBody.fileName("TX-AB12CD34", "XML"));
        assertEquals("TX-AB12CD34.json", TransmissionBody.fileName("TX-AB12CD34", "JSON"));
        // nothing from an id can break out of the header value or the path
        assertFalse(TransmissionBody.fileName("a/b\"c\r\nd", "JSON").matches(".*[/\"\\r\\n].*"));
    }
}
