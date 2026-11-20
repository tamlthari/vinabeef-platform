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
import java.util.Base64;

@Service
@Slf4j
@RequiredArgsConstructor
public class DirectMqttIngestionService implements MqttCallbackExtended {

    // private final SafetrustProcessingService processingService;
    private final TelemetryEventProcessor eventProcessor;
    private final IngestionHealthService healthService;
    private final ObjectMapper objectMapper;

    @Value("${vinabeef.ingestion.safetrust.broker-url}")
    private String brokerUrl;

    @Value("${vinabeef.ingestion.safetrust.device-key}")
    private String deviceKey;

    @Value("${vinabeef.ingestion.safetrust.company-key}")
    private String companyKey;

    @Value("${vinabeef.ingestion.safetrust.token}")
    private String token;

    private MqttAsyncClient mqttClient;

    @PostConstruct
    public void start() {
        connectClient();
    }

    public synchronized void connectClient() {
        try {
            // 1. Align Client ID with Safetrust's 'Web_' requirement
            String clientId = "Web_Direct_" + deviceKey;
            
            // 2. Use MemoryPersistence to prevent disk-lock contention
            mqttClient = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());
            mqttClient.setCallback(this);

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setUserName(deviceKey);
            options.setPassword(("token/" + token).toCharArray());
            options.setKeepAliveInterval(30);

            // 3. Disable SSL & Hostname validation (equivalent to client.tls_insecure_set(True) in Python)
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }}, new SecureRandom());
            
            options.setSocketFactory(sslContext.getSocketFactory());
            options.setHttpsHostnameVerificationEnabled(false); // CRUCIAL for wss:// demo brokers

            log.info("[PRIMARY_DIRECT_MQTT] Establishing direct connection to {} as {}", brokerUrl, clientId);
            IMqttToken connToken = mqttClient.connect(options);
            connToken.waitForCompletion(10000);

            String topic = String.format("Web/%s/Monitoring/%s", companyKey, deviceKey);
            String pwdTopic = "token/" + token;

            mqttClient.subscribe(new String[]{pwdTopic, topic}, new int[]{2, 2});
            healthService.setDirectMqttConnected(true);
            healthService.setDisconnectReason("NONE");
            log.info("[PRIMARY_DIRECT_MQTT] Connected & Subscribed to topic: {}", topic);

        } catch (MqttException me) {
            String rootCause = me.getCause() != null ? me.getCause().getMessage() : me.getMessage();
            healthService.setDirectMqttConnected(false);
            healthService.setDisconnectReason("ReasonCode " + me.getReasonCode() + ": " + rootCause);
            log.error("[PRIMARY_DIRECT_MQTT] Connection failed [ReasonCode: {}]: {}", me.getReasonCode(), me.getMessage());
            if (me.getCause() != null) {
                log.error(" -> Root Cause: {}", me.getCause().getMessage(), me.getCause());
            }
        } catch (Exception e) {
            healthService.setDirectMqttConnected(false);
            healthService.setDisconnectReason("General error: " + e.getMessage());
            log.error("[PRIMARY_DIRECT_MQTT] Unexpected initialization error: {}", e.getMessage(), e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        healthService.setDirectMqttConnected(true);
        healthService.setDisconnectReason("NONE");
        log.info("[PRIMARY_DIRECT_MQTT] Connection active (reconnect={}) to {}", reconnect, serverURI);
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
        try {
            // Use resilient decoder instead of Base64.getDecoder().decode(payloadBytes)
            String jsonString = decodeSafetrustPayload(message.getPayload());

            SafetrustEventDto dto = objectMapper.readValue(jsonString, SafetrustEventDto.class);
            dto.setRawPayload(jsonString);

            // processingService.processSafetrustRead(dto, "DIRECT_MQTT", tIngressNano);
            eventProcessor.processSafetrustRead(dto, "DIRECT_MQTT", tIngressNano);

        } catch (Exception ex) {
            log.error("[PRIMARY_DIRECT_MQTT] Message parsing error: {}", ex.getMessage());
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {}

    @PreDestroy
    public void stop() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                mqttClient.close();
            }
        } catch (Exception ignored) {}
    }

    private String decodeSafetrustPayload(byte[] rawBytes) {
        String rawStr = new String(rawBytes, StandardCharsets.UTF_8).trim();

        // 1. Check if the message is already unencoded plain JSON
        if (rawStr.startsWith("{") && rawStr.endsWith("}")) {
            return rawStr;
        }

        // 2. Strip wrapping quotes if payload was serialized as a quoted string literal
        if (rawStr.startsWith("\"") && rawStr.endsWith("\"") && rawStr.length() > 1) {
            rawStr = rawStr.substring(1, rawStr.length() - 1).trim();
        }

        // 3. Remove whitespace, carriage returns, and newlines (matching Python behavior)
        String sanitized = rawStr.replaceAll("\\s+", "");

        // 4. Convert URL-safe base64 ('-' and '_') to standard base64 ('+' and '/')
        sanitized = sanitized.replace('-', '+').replace('_', '/');

        // 5. Restore missing '=' padding if omitted by the publisher
        int remainder = sanitized.length() % 4;
        if (remainder == 2) {
            sanitized += "==";
        } else if (remainder == 3) {
            sanitized += "=";
        }

        // 6. Decode using MIME decoder (resilient to extraneous characters)
        byte[] decoded = Base64.getMimeDecoder().decode(sanitized.getBytes(StandardCharsets.UTF_8));
        return new String(decoded, StandardCharsets.UTF_8);
    }
}