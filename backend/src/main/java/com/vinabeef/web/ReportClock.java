package com.vinabeef.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The one place that talks to the national animal-disease system (NADCS).
 *
 * <p>The destination and the credential come from the server's configuration ({@code nadcs.*}), never from the
 * request, so a user cannot point the server at an arbitrary address (SSRF) or read the key back.
 * Two modes:
 * <ul>
 *   <li>{@code SIMULATION} (default): nothing leaves the server; the result says so (delivery status SIMULATED).</li>
 *   <li>{@code LIVE}: the payload is POSTed to the configured endpoint. The result carries what the agency really
 *       answered: DELIVERED (2xx), REJECTED (any other status) or UNREACHABLE (no answer).</li>
 * </ul>
 *
 * <p><b>Test server.</b> While the agency's real API is not available, {@code nadcs.endpoint} keeps the agency's address
 * as the <i>nominal</i> destination (it is what the screens and the transmission log show), and
 * {@code nadcs.target-url} says where the HTTP call <i>really</i> goes: a test server that validates the report
 * (see nadcs-mock/). The result then says so ("DELIVERED via test server host: ..."), so a log entry can never be
 * mistaken for a delivery to the agency. A sleeping free-tier host is woken first (GET /health, long timeout), because
 * the first request after an idle period takes a minute.
 */
@Component
public class NadcsGateway {

    public static final String SIMULATED = "SIMULATED";
    public static final String DELIVERED = "DELIVERED";
    public static final String REJECTED = "REJECTED";
    public static final String UNREACHABLE = "UNREACHABLE";

    /** What a transmit attempt came to. {@code httpStatus} is null when no HTTP answer exists. */
    public record Result(String deliveryStatus, Integer httpStatus, String summary) {
    }

    /** A LIVE transmit that cannot even be attempted (missing endpoint/key, bad URL): nothing is recorded as sent. */
    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException(String message) {
            super(message);
        }
    }

    private final boolean live;
    private final String endpoint;
    /** Where the HTTP call really goes: {@code endpoint} unless a test server is configured. */
    private final String target;
    private final String apiKey;
    private final String apiKeyHeader;
    private final Duration timeout;
    private final Duration warmup;
    private final List<String> insecureHosts;
    private final HttpClient client;

    public NadcsGateway(@Value("${nadcs.mode:SIMULATION}") String mode,
                        @Value("${nadcs.endpoint:https://api.nadcs.dah.gov.vn/v2/surveillance/herd-health}") String endpoint,
                        @Value("${nadcs.api-key:}") String apiKey,
                        @Value("${nadcs.api-key-header:X-API-Key}") String apiKeyHeader,
                        @Value("${nadcs.timeout-ms:5000}") int timeoutMs,
                        @Value("${nadcs.insecure-hosts:}") String insecureHosts,
                        @Value("${nadcs.target-url:}") String targetUrl,
                        @Value("${nadcs.warmup-timeout-ms:70000}") int warmupTimeoutMs) {
        this.live = "LIVE".equalsIgnoreCase(mode == null ? "" : mode.trim());
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.target = targetUrl == null || targetUrl.isBlank() ? this.endpoint : targetUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.apiKeyHeader = apiKeyHeader == null || apiKeyHeader.isBlank() ? "X-API-Key" : apiKeyHeader.trim();
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
        this.warmup = Duration.ofMillis(Math.max(0, warmupTimeoutMs));
        this.insecureHosts = Arrays.stream((insecureHosts == null ? "" : insecureHosts).split(","))
                .map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList();
        // redirects are never followed: a redirect must not carry the API key to another host
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public boolean isLive() {
        return live;
    }

    public String endpoint() {
        return endpoint;
    }

    public boolean apiKeyConfigured() {
        return !apiKey.isEmpty();
    }

    /** True when the HTTP call goes to a test server instead of the address shown as the agency's. */
    public boolean isTestServer() {
        return !target.equals(endpoint);
    }

    /** Host the call really goes to (for the banner and the log); empty when it cannot be read. */
    public String targetHost() {
        try {
            String h = URI.create(target).getHost();
            return h == null ? "" : h;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Sends the payload (LIVE) or reports honestly that it did not (SIMULATION). Never throws for an unreachable agency. */
    public Result send(String payload, String format, int recordCount) {
        if (!live) {
            return new Result(SIMULATED, null,
                    "SIMULATED: nothing was sent to the agency (nadcs.mode=SIMULATION). " + recordCount + " records were prepared.");
        }
        URI uri = validatedTarget();
        if (apiKey.isEmpty()) {
            throw new NotConfiguredException("nadcs.mode is LIVE but nadcs.api-key is empty; nothing was sent.");
        }
        boolean viaTestServer = isTestServer();
        if (viaTestServer) {
            wakeUp(uri);
        }
        Result result;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(timeout)
                    .header("Content-Type", "XML".equalsIgnoreCase(format) ? "application/xml" : "application/json")
                    .header("Accept", "application/json")
                    .header(apiKeyHeader, apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            result = classify(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = new Result(UNREACHABLE, null, "UNREACHABLE: interrupted while waiting for the agency.");
        } catch (Exception e) {
            result = new Result(UNREACHABLE, null, "UNREACHABLE: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + shorten(e.getMessage(), 160)));
        }
        return viaTestServer ? markTestServer(result, targetHost()) : result;
    }

    /**
     * A free-tier host that has gone to sleep needs about a minute for its first answer, longer than the normal
     * timeout. One patient GET /health wakes it; the outcome is ignored here, because the real call that follows
     * reports a host that stays down as UNREACHABLE.
     */
    private void wakeUp(URI uri) {
        if (warmup.isZero()) {
            return;
        }
        try {
            URI health = new URI(uri.getScheme(), uri.getAuthority(), "/health", null, null);
            HttpRequest ping = HttpRequest.newBuilder().uri(health).timeout(warmup).GET().build();
            client.send(ping, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // the POST that follows decides what is reported
        }
    }

    /** "DELIVERED: HTTP 202 ..." becomes "DELIVERED via test server host: HTTP 202 ...". Pure, so it can be tested. */
    static Result markTestServer(Result r, String host) {
        String label = host == null || host.isEmpty() ? "test server" : "test server " + host;
        String summary = r.summary().replaceFirst("^([A-Z]+)", "$1 via " + java.util.regex.Matcher.quoteReplacement(label));
        return new Result(r.deliveryStatus(), r.httpStatus(), summary);
    }

    /** 2xx is delivered; every other status (including a redirect) is a rejection. Pure, so it can be tested without a network. */
    static Result classify(int status, String body) {
        boolean ok = status >= 200 && status < 300;
        String text = answerText(body, ok ? 220 : 600);
        if (ok) {
            return new Result(DELIVERED, status, "DELIVERED: HTTP " + status + (text.isEmpty() ? "" : " " + text));
        }
        return new Result(REJECTED, status, "REJECTED: HTTP " + status + (text.isEmpty() ? "" : " " + text));
    }

    private static final java.util.regex.Pattern SUMMARY_FIELD =
            java.util.regex.Pattern.compile("\"summary\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * What the agency said, as one short line: its own "summary" field when the answer is JSON that has one
     * (the test server puts the receipt or the first validation errors there), else the body with whitespace squeezed.
     */
    static String answerText(String body, int max) {
        String b = body == null ? "" : body;
        var m = SUMMARY_FIELD.matcher(b);
        String text = m.find()
                ? m.group(1).replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\")
                : b;
        return shorten(text.replaceAll("\\s+", " ").trim(), max);
    }

    private URI validatedTarget() {
        String name = isTestServer() ? "nadcs.target-url" : "nadcs.endpoint";
        if (target.isEmpty()) {
            throw new NotConfiguredException("nadcs.mode is LIVE but " + name + " is empty; nothing was sent.");
        }
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException e) {
            throw new NotConfiguredException(name + " is not a valid URL; nothing was sent.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new NotConfiguredException(name + " has no host; nothing was sent.");
        }
        boolean https = "https".equals(scheme);
        boolean allowedPlain = "http".equals(scheme) && insecureHosts.contains(host);
        if (!https && !allowedPlain) {
            throw new NotConfiguredException(name + " must be https (plain http only for hosts listed in nadcs.insecure-hosts); nothing was sent.");
        }
        return uri;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
