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
    private final String apiKey;
    private final String apiKeyHeader;
    private final Duration timeout;
    private final List<String> insecureHosts;
    private final HttpClient client;

    public NadcsGateway(@Value("${nadcs.mode:SIMULATION}") String mode,
                        @Value("${nadcs.endpoint:https://api.nadcs.dah.gov.vn/v2/surveillance/herd-health}") String endpoint,
                        @Value("${nadcs.api-key:}") String apiKey,
                        @Value("${nadcs.api-key-header:X-API-Key}") String apiKeyHeader,
                        @Value("${nadcs.timeout-ms:5000}") int timeoutMs,
                        @Value("${nadcs.insecure-hosts:}") String insecureHosts) {
        this.live = "LIVE".equalsIgnoreCase(mode == null ? "" : mode.trim());
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.apiKeyHeader = apiKeyHeader == null || apiKeyHeader.isBlank() ? "X-API-Key" : apiKeyHeader.trim();
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
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

    /** Sends the payload (LIVE) or reports honestly that it did not (SIMULATION). Never throws for an unreachable agency. */
    public Result send(String payload, String format, int recordCount) {
        if (!live) {
            return new Result(SIMULATED, null,
                    "SIMULATED: nothing was sent to the agency (nadcs.mode=SIMULATION). " + recordCount + " records were prepared.");
        }
        URI uri = validatedEndpoint();
        if (apiKey.isEmpty()) {
            throw new NotConfiguredException("nadcs.mode is LIVE but nadcs.api-key is empty; nothing was sent.");
        }
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
            return classify(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(UNREACHABLE, null, "UNREACHABLE: interrupted while waiting for the agency.");
        } catch (Exception e) {
            return new Result(UNREACHABLE, null, "UNREACHABLE: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + shorten(e.getMessage(), 160)));
        }
    }

    /** 2xx is delivered; every other status (including a redirect) is a rejection. Pure, so it can be tested without a network. */
    static Result classify(int status, String body) {
        String text = shorten(body == null ? "" : body.replaceAll("\\s+", " ").trim(), 180);
        if (status >= 200 && status < 300) {
            return new Result(DELIVERED, status, "DELIVERED: HTTP " + status + (text.isEmpty() ? "" : " " + text));
        }
        return new Result(REJECTED, status, "REJECTED: HTTP " + status + (text.isEmpty() ? "" : " " + text));
    }

    private URI validatedEndpoint() {
        if (endpoint.isEmpty()) {
            throw new NotConfiguredException("nadcs.mode is LIVE but nadcs.endpoint is empty; nothing was sent.");
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new NotConfiguredException("nadcs.endpoint is not a valid URL; nothing was sent.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new NotConfiguredException("nadcs.endpoint has no host; nothing was sent.");
        }
        boolean https = "https".equals(scheme);
        boolean allowedPlain = "http".equals(scheme) && insecureHosts.contains(host);
        if (!https && !allowedPlain) {
            throw new NotConfiguredException("nadcs.endpoint must be https (plain http only for hosts listed in nadcs.insecure-hosts); nothing was sent.");
        }
        return uri;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
