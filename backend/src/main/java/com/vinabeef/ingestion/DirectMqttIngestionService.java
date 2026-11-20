package com.vinabeef.ingestion;

// import com.fasterxml.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectMapper;
// import com.vinabeef.movement.SafetrustProcessingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class DirectMqttIngestionService implements MqttCallbackExtended {

    private final TelemetryEventProcessor eventProcessor;
    private final IngestionHealthService healthService;
    private final ObjectMapper objectMapper;
    private final DeadLetterService deadLetterService;

    @Value("${vinabeef.ingestion.safetrust.broker-url}")
    private String brokerUrl;

    @Value("${vinabeef.ingestion.safetrust.device-key}")
    private String deviceKey;

    @Value("${vinabeef.ingestion.safetrust.company-key}")
    private String companyKey;

    @Value("${vinabeef.ingestion.safetrust.token}")
    private String token;

    /**
     * Added to the MQTT client id. Two programs that connect with the same client id kick each other off the
     * broker, so every running copy of the backend (deployed site, local machine) needs its own suffix.
     */
    @Value("${vinabeef.ingestion.safetrust.client-id-suffix:}")
    private String clientIdSuffix;

    /**
     * false keeps a persistent broker session, so the broker can hold messages while this backend is down.
     * Needs confirmation that the Safetrust broker honours it, so the default is the previous behaviour.
     */
    @Value("${vinabeef.ingestion.safetrust.clean-session:true}")
    private boolean cleanSession;

    private static final int MAX_BACKOFF_SECONDS = 60;

    private MqttAsyncClient mqttClient;
    private final ScheduledExecutorService reconnector = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "safetrust-mqtt-connect");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger connectAttempts = new AtomicInteger();

    @PostConstruct
    public void start() {
        // Never block application start-up on the broker, and keep trying until the first connection works.
        // Paho's automatic reconnect only starts after one successful connection, so a failed first attempt
        // (barn internet down at boot, DNS not ready) would otherwise leave the subscriber dead for good.
        reconnector.execute(this::connectWithRetry);
    }

    private void connectWithRetry() {
        if (connectClient()) {
            connectAttempts.set(0);
            return;
        }
        int attempt = connectAttempts.incrementAndGet();
        long delay = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempt, 6));
        log.warn("[PRIMARY_DIRECT_MQTT] Not connected (attempt {}). Next try in {} s.", attempt, delay);
        reconnector.schedule(this::connectWithRetry, delay, TimeUnit.SECONDS);
    }

    /** One connection attempt. Subscribing happens in {@link #connectComplete}. Returns true when connected. */
    public synchronized boolean connectClient() {
        try {
            closeQuietly();
            String clientId = clientId();

            // MemoryPersistence prevents disk-lock contention
            mqttClient = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());
            mqttClient.setCallback(this);

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(cleanSession);
            options.setAutomaticReconnect(true);
            options.setMaxReconnectDelay(30_000);
            options.setUserName(deviceKey);
            options.setPassword(("token/" + token).toCharArray());
            options.setKeepAliveInterval(30);

            // Disable SSL & hostname validation (equivalent to client.tls_insecure_set(True) in Python)
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }}, new SecureRandom());

            options.setSocketFactory(sslContext.getSocketFactory());
            options.setHttpsHostnameVerificationEnabled(false); // CRUCIAL for wss:// demo brokers

            log.info("[PRIMARY_DIRECT_MQTT] Establishing direct connection to {} as {} (cleanSession={})",
                    brokerUrl, clientId, cleanSession);
            IMqttToken connToken = mqttClient.connect(options);
            connToken.waitForCompletion(10000);
            return mqttClient.isConnected();

        } catch (MqttException me) {
            String rootCause = me.getCause() != null ? me.getCause().getMessage() : me.getMessage();
            healthService.setDirectMqttConnected(false);
            healthService.setDisconnectReason("ReasonCode " + me.getReasonCode() + ": " + rootCause);
            log.error("[PRIMARY_DIRECT_MQTT] Connection failed [ReasonCode: {}]: {}", me.getReasonCode(), me.getMessage());
            return false;
        } catch (Exception e) {
            healthService.setDirectMqttConnected(false);
            healthService.setDisconnectReason("General error: " + e.getMessage());
            log.error("[PRIMARY_DIRECT_MQTT] Unexpected initialization error: {}", e.getMessage(), e);
            return false;
        }
    }

    private String clientId() {
        // Safetrust requires the 'Web_' prefix.
        return "Web_Direct_" + deviceKey + (clientIdSuffix == null || clientIdSuffix.isBlank() ? "" : "_" + clientIdSuffix.trim());
    }

    private void closeQuietly() {
        if (mqttClient == null) {
            return;
        }
        try {
            mqttClient.setCallback(null);
            if (mqttClient.isConnected()) {
                mqttClient.disconnect();
            }
            mqttClient.close();
        } catch (Exception ignored) {
            // a half-open client from a failed attempt
        }
        mqttClient = null;
    }

    private void subscribeAll() {
        String topic = String.format("Web/%s/Monitoring/%s", companyKey, deviceKey);
        String pwdTopic = "token/" + token;
        try {
            mqttClient.subscribe(new String[]{pwdTopic, topic}, new int[]{2, 2}, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    log.info("[PRIMARY_DIRECT_MQTT] Subscribed to topic: {}", topic);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    healthService.setDisconnectReason("Subscribe failed: " + exception.getMessage());
                    log.error("[PRIMARY_DIRECT_MQTT] Subscribe to {} failed: {}", topic, exception.getMessage());
                }
            });
        } catch (MqttException me) {
            log.error("[PRIMARY_DIRECT_MQTT] Subscribe request failed: {}", me.getMessage());
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        healthService.setDirectMqttConnected(true);
        healthService.setDisconnectReason("NONE");
        log.info("[PRIMARY_DIRECT_MQTT] Connection active (reconnect={}) to {}", reconnect, serverURI);
        // A clean session forgets its subscriptions when the connection drops. Paho reconnects on its own but
        // does not resubscribe, so without this the backend stays "connected" and receives nothing after the
        // first network outage.
        subscribeAll();
    }

    @Override
    public void connectionLost(Throwable cause) {
        String reason = cause != null ? cause.getMessage() : "Connection lost";
        healthService.setDirectMqttConnected(false);
        healthService.setDisconnectReason(reason);
        log.warn("[PRIMARY_DIRECT_MQTT] Disconnected: {}. Failover required.", reason);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        long tIngressNano = System.nanoTime();
        healthService.recordDirectHeartbeat();
        String jsonString = null;
        Long eventKey = null;
        try {
            jsonString = PayloadDecoder.decode(message.getPayload(), token);

            SafetrustEventDto dto = objectMapper.readValue(jsonString, SafetrustEventDto.class);
            dto.setRawPayload(jsonString);
            eventKey = dto.getEventKey();

            eventProcessor.processSafetrustRead(dto, "DIRECT_MQTT", tIngressNano);

        } catch (PayloadDecoder.NotAnEventException notAnEvent) {
            // Not an event (a broker notice, a JSON array...). One line, no stack trace, and kept for inspection.
            log.warn("[PRIMARY_DIRECT_MQTT] A message on {} is not a Safetrust event: {}", topic, notAnEvent.getMessage());
            keepDeadLetter(null, "NOT_AN_EVENT: " + notAnEvent.getMessage(),
                    new String(message.getPayload(), StandardCharsets.UTF_8));
        } catch (Exception ex) {
            // Returning normally acknowledges the message to the broker, so it will not be sent again.
            // Keep it instead of dropping it, and never throw (that would close the connection).
            log.error("[PRIMARY_DIRECT_MQTT] Could not process message on {} (eventKey={}): {}",
                    topic, eventKey, ex.getMessage(), ex);
            keepDeadLetter(eventKey, ex.getMessage(),
                    jsonString != null ? jsonString : new String(message.getPayload(), StandardCharsets.UTF_8));
        }
    }

    private void keepDeadLetter(Long eventKey, String reason, String payload) {
        try {
            deadLetterService.store("DIRECT_MQTT", eventKey, reason, payload);
        } catch (Exception dlq) {
            log.error("[PRIMARY_DIRECT_MQTT] EVENT NOT SAVED and dead letter failed too ({}). Payload: {}",
                    dlq.getMessage(), payload);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {}

    @PreDestroy
    public void stop() {
        reconnector.shutdownNow();
        closeQuietly();
    }
}
