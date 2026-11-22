package com.vinabeef.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gateway against a real HTTP server on localhost: what is sent, and what is recorded as the outcome. */
class NadcsGatewayTest {

    private static NadcsGateway gateway(String mode, String endpoint, String key, String insecureHosts) {
        return new NadcsGateway(mode, endpoint, key, "X-API-Key", 1500, insecureHosts, "", 0);
    }

    /** nominal agency address, real call to a test server */
    private static NadcsGateway viaTestServer(String key, String target, int warmupMs) {
        return new NadcsGateway("LIVE", "https://api.nadcs.dah.gov.vn/v2/surveillance/herd-health", key, "X-API-Key", 1500, "127.0.0.1", target, warmupMs);
    }

    private static HttpServer server(int status, String body, AtomicReference<String> seenKey, AtomicReference<String> seenBody) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/herd-health", ex -> {
            seenKey.set(ex.getRequestHeaders().getFirst("X-API-Key"));
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        return s;
    }

    @Test
    void simulationNeverTouchesTheNetworkAndSaysSo() {
        // the endpoint does not even resolve: a simulation must not try
        var r = gateway("SIMULATION", "https://api.nadcs.invalid/x", "", "").send("{}", "JSON", 7);

        assertEquals("SIMULATED", r.deliveryStatus());
        assertNull(r.httpStatus());
        assertTrue(r.summary().startsWith("SIMULATED"));
        assertTrue(r.summary().contains("7 records"));
    }

    @Test
    void anUnknownOrEmptyModeIsASimulation() {
        assertFalse(gateway("", "https://x.invalid", "k", "").isLive());
        assertFalse(gateway("typo", "https://x.invalid", "k", "").isLive());
        assertTrue(gateway("live", "https://x.invalid", "k", "").isLive());
    }

    @Test
    void liveDeliversPayloadAndKeyAndRecordsTheAgencyAnswer() throws Exception {
        var key = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var s = server(202, "{\"receipt\":\"R-42\"}", key, body);
        try {
            var r = gateway("LIVE", "http://127.0.0.1:" + s.getAddress().getPort() + "/herd-health", "secret-1", "127.0.0.1")
                    .send("{\"records\":[1]}", "JSON", 1);

            assertEquals("DELIVERED", r.deliveryStatus());
            assertEquals(202, r.httpStatus());
            assertTrue(r.summary().contains("R-42"));
            assertEquals("secret-1", key.get());
            assertEquals("{\"records\":[1]}", body.get());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void liveRecordsARejectionWithItsStatus() throws Exception {
        var s = server(422, "schema mismatch", new AtomicReference<>(), new AtomicReference<>());
        try {
            var r = gateway("LIVE", "http://127.0.0.1:" + s.getAddress().getPort() + "/herd-health", "k", "127.0.0.1").send("{}", "JSON", 0);

            assertEquals("REJECTED", r.deliveryStatus());
            assertEquals(422, r.httpStatus());
            assertTrue(r.summary().contains("schema mismatch"));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void aRedirectIsNotFollowedAndCountsAsRejected() throws Exception {
        var s = server(302, "", new AtomicReference<>(), new AtomicReference<>());
        try {
            var r = gateway("LIVE", "http://127.0.0.1:" + s.getAddress().getPort() + "/herd-health", "k", "127.0.0.1").send("{}", "JSON", 0);

            assertEquals("REJECTED", r.deliveryStatus());
            assertEquals(302, r.httpStatus());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void liveWithNobodyListeningIsUnreachableWithoutAStatus() throws Exception {
        var closed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = closed.getAddress().getPort();
        closed.stop(0);   // the port is now free and nothing answers

        var r = gateway("LIVE", "http://127.0.0.1:" + port + "/herd-health", "k", "127.0.0.1").send("{}", "JSON", 0);

        assertEquals("UNREACHABLE", r.deliveryStatus());
        assertNull(r.httpStatus());
    }

    @Test
    void liveRefusesToSendWithoutAKeyOrOverPlainHttp() {
        assertThrows(NadcsGateway.NotConfiguredException.class,
                () -> gateway("LIVE", "https://api.nadcs.invalid/x", "", "").send("{}", "JSON", 0));
        assertThrows(NadcsGateway.NotConfiguredException.class,
                () -> gateway("LIVE", "http://api.nadcs.invalid/x", "k", "").send("{}", "JSON", 0));
        assertThrows(NadcsGateway.NotConfiguredException.class,
                () -> gateway("LIVE", "", "k", "").send("{}", "JSON", 0));
        assertThrows(NadcsGateway.NotConfiguredException.class,
                () -> gateway("LIVE", "not a url", "k", "").send("{}", "JSON", 0));
        // plain http is allowed only for a host that is listed
        assertThrows(NadcsGateway.NotConfiguredException.class,
                () -> gateway("LIVE", "http://evil.invalid/x", "k", "127.0.0.1").send("{}", "JSON", 0));
    }

    @Test
    void classifyTreatsOnlyTwoHundredsAsDelivered() {
        assertEquals("DELIVERED", NadcsGateway.classify(200, "ok").deliveryStatus());
        assertEquals("DELIVERED", NadcsGateway.classify(299, "").deliveryStatus());
        assertEquals("REJECTED", NadcsGateway.classify(300, "").deliveryStatus());
        assertEquals("REJECTED", NadcsGateway.classify(500, "boom").deliveryStatus());
        assertEquals("REJECTED", NadcsGateway.classify(199, "").deliveryStatus());
    }

    @Test
    void theApiKeyIsNeverPartOfTheSummary() throws Exception {
        var s = server(200, "fine", new AtomicReference<>(), new AtomicReference<>());
        try {
            var r = gateway("LIVE", "http://127.0.0.1:" + s.getAddress().getPort() + "/herd-health", "TOP-SECRET-KEY", "127.0.0.1").send("{}", "JSON", 0);

            assertFalse(r.summary().contains("TOP-SECRET-KEY"));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void theNominalEndpointStaysTheAgencyAndTheCallGoesToTheTestServer() throws Exception {
        var key = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var s = server(202, "{\"mock\":true,\"status\":\"ACCEPTED\",\"summary\":\"Accepted: receipt NADCS-MOCK-1 (3 records).\"}", key, body);
        try {
            var g = viaTestServer("secret", "http://127.0.0.1:" + s.getAddress().getPort() + "/herd-health", 0);
            assertEquals("https://api.nadcs.dah.gov.vn/v2/surveillance/herd-health", g.endpoint(), "what the screens and the log show");
            assertTrue(g.isTestServer());
            assertEquals("127.0.0.1", g.targetHost());

            var r = g.send("{\"records\":[]}", "JSON", 3);

            assertEquals("DELIVERED", r.deliveryStatus());
            assertEquals(202, r.httpStatus());
            assertEquals("DELIVERED via test server 127.0.0.1: HTTP 202 Accepted: receipt NADCS-MOCK-1 (3 records).", r.summary());
            assertEquals("secret", key.get());
            assertEquals("{\"records\":[]}", body.get());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void withoutATargetTheCallGoesToTheEndpointAndNothingSaysTestServer() throws Exception {
        var s = server(202, "{}", new AtomicReference<>(), new AtomicReference<>());
        try {
            var g = gateway("LIVE", "http://127.0.0.1:" + s.getAddress().getPort() + "/herd-health", "k", "127.0.0.1");
            assertFalse(g.isTestServer());
            assertFalse(g.send("{}", "JSON", 0).summary().contains("test server"));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void aRejectionByTheTestServerShowsItsOwnSummaryAndIsLabelled() throws Exception {
        var s = server(422, "{\"status\":\"REJECTED\",\"summary\":\"Rejected: 2 validation errors. recordCount: says 3 but 1 records were sent; records[0].coverage_pct: 99.0 does not match 157/200 = 78.5\",\"errors\":[]}",
                new AtomicReference<>(), new AtomicReference<>());
        try {
            var r = viaTestServer("k", "http://127.0.0.1:" + s.getAddress().getPort() + "/herd-health", 0).send("{}", "JSON", 1);
            assertEquals("REJECTED", r.deliveryStatus());
            assertEquals(422, r.httpStatus());
            assertTrue(r.summary().startsWith("REJECTED via test server 127.0.0.1: HTTP 422 Rejected: 2 validation errors."), r.summary());
            assertTrue(r.summary().contains("records[0].coverage_pct"));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void aSleepingTestServerIsWokenWithAHealthCallBeforeTheReport() throws Exception {
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            calls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(202, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        try {
            viaTestServer("k", "http://127.0.0.1:" + s.getAddress().getPort() + "/v2/surveillance/herd-health", 5000).send("{}", "JSON", 0);
            assertEquals(java.util.List.of("GET /health", "POST /v2/surveillance/herd-health"), calls);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void aTestServerThatIsDownIsUnreachableAndLabelled() {
        var r = viaTestServer("k", "http://127.0.0.1:1/herd-health", 0).send("{}", "JSON", 0);
        assertEquals("UNREACHABLE", r.deliveryStatus());
        assertNull(r.httpStatus());
        assertTrue(r.summary().startsWith("UNREACHABLE via test server 127.0.0.1: "), r.summary());
    }

    @Test
    void theTargetIsValidatedLikeTheEndpoint() {
        var plain = new NadcsGateway("LIVE", "https://api.nadcs.dah.gov.vn/x", "k", "X-API-Key", 1500, "", "http://evil.example/x", 0);
        var e = assertThrows(NadcsGateway.NotConfiguredException.class, () -> plain.send("{}", "JSON", 0));
        assertTrue(e.getMessage().contains("nadcs.target-url must be https"), e.getMessage());
        var bad = new NadcsGateway("LIVE", "https://api.nadcs.dah.gov.vn/x", "k", "X-API-Key", 1500, "", "https://", 0);
        assertThrows(NadcsGateway.NotConfiguredException.class, () -> bad.send("{}", "JSON", 0));
    }

    @Test
    void simulationIgnoresTheTargetCompletely() {
        var g = new NadcsGateway("SIMULATION", "https://api.nadcs.dah.gov.vn/x", "", "X-API-Key", 1500, "", "https://nadcs-mock.invalid/x", 70000);
        var r = g.send("{}", "JSON", 2);
        assertEquals("SIMULATED", r.deliveryStatus());
        assertFalse(r.summary().contains("test server"));
    }

    @Test
    void markTestServerOnlyChangesTheLeadingStatusWord() {
        var r = NadcsGateway.markTestServer(new NadcsGateway.Result("DELIVERED", 202, "DELIVERED: HTTP 202 OK $1 \\ done"), "h.example");
        assertEquals("DELIVERED via test server h.example: HTTP 202 OK $1 \\ done", r.summary());
        assertEquals("REJECTED via test server: HTTP 400", NadcsGateway.markTestServer(new NadcsGateway.Result("REJECTED", 400, "REJECTED: HTTP 400"), "").summary());
    }

    @Test
    void answerTextPrefersTheSummaryFieldAndFallsBackToTheBody() {
        assertEquals("Accepted: receipt X \"quoted\"", NadcsGateway.answerText("{\"a\":1,\"summary\":\"Accepted: receipt X \\\"quoted\\\"\",\"b\":2}", 100));
        assertEquals("plain   text body".replaceAll("\\s+", " "), NadcsGateway.answerText("plain   text body", 100));
        assertEquals("", NadcsGateway.answerText(null, 100));
        assertEquals("abcde...", NadcsGateway.answerText("abcdefghij", 5));
    }

    /** records the reference header and the exact bytes of the request */
    private static HttpServer capturing(AtomicReference<String> ref, AtomicReference<byte[]> bytes, AtomicReference<String> contentType) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/herd-health", ex -> {
            ref.set(ex.getRequestHeaders().getFirst("X-Client-Reference"));
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            bytes.set(ex.getRequestBody().readAllBytes());
            byte[] out = "{\"summary\":\"Accepted: receipt R-1\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(202, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        return s;
    }

    @Test
    void theTransmissionIdTravelsAsAClientReferenceAndTheBodyArrivesByteForByte() throws Exception {
        var ref = new AtomicReference<String>();
        var bytes = new AtomicReference<byte[]>();
        var type = new AtomicReference<String>();
        var s = capturing(ref, bytes, type);
        try {
            // non-ASCII text: the hash of what is stored must equal the hash of what the server received
            String payload = "{\"farm\":\"Trang trại Đắk Lắk\",\"cow\":\"bò 🐄\"}";
            var r = viaTestServer("k", "http://127.0.0.1:" + s.getAddress().getPort() + "/herd-health", 0)
                    .send(payload, "JSON", 1, "TX-AB12CD34");

            assertEquals("DELIVERED", r.deliveryStatus());
            assertEquals("TX-AB12CD34", ref.get());
            assertEquals("application/json", type.get());
            assertEquals(TransmissionBody.sha256(payload.getBytes(StandardCharsets.UTF_8)), TransmissionBody.sha256(bytes.get()));
            assertEquals(payload, new String(bytes.get(), StandardCharsets.UTF_8));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void noReferenceHeaderIsSentWithoutOneOrForAnUnsafeValue() throws Exception {
        var ref = new AtomicReference<String>("untouched");
        var s = capturing(ref, new AtomicReference<>(), new AtomicReference<>());
        try {
            var g = viaTestServer("k", "http://127.0.0.1:" + s.getAddress().getPort() + "/herd-health", 0);
            g.send("{}", "JSON", 0);
            assertNull(ref.get());
            ref.set("untouched");
            g.send("{}", "JSON", 0, "TX-1\r\nInjected: yes");
            assertNull(ref.get());
            g.send("{}", "XML", 0, "x".repeat(65));
            assertNull(ref.get());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void theTestServerPageIsTheSchemeAndHostOfTheTargetAndOnlyForATestServer() {
        var g = viaTestServer("k", "https://nadcs-mock.onrender.com/v2/surveillance/herd-health", 0);
        assertEquals("https://nadcs-mock.onrender.com/ui", g.testServerUiUrl());
        assertEquals("http://127.0.0.1:9099/ui", viaTestServer("k", "http://127.0.0.1:9099/v2/x", 0).testServerUiUrl());
        assertEquals("", gateway("LIVE", "https://api.nadcs.dah.gov.vn/v2/surveillance/herd-health", "k", "").testServerUiUrl());
    }
}
