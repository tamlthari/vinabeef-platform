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
        return new NadcsGateway(mode, endpoint, key, "X-API-Key", 1500, insecureHosts);
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
}
