package com.vinabeef.ingestion.adapter;

import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.ingestion.port.EventPayloadParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * {@link EventPayloadParser} with the application's Jackson mapper. The payload may be JSON text, or the Base64 text a dead letter
 * keeps when the MQTT message could not be read at the time ({@link PayloadDecoder} reads both).
 */
@Component
@RequiredArgsConstructor
public class JacksonEventPayloadParser implements EventPayloadParser {

    private final ObjectMapper objectMapper;

    @Override
    public SafetrustEventDto parse(String payload) {
        try {
            String json = PayloadDecoder.decode(payload.getBytes(StandardCharsets.UTF_8), null);
            return objectMapper.readValue(json, SafetrustEventDto.class);
        } catch (Exception ex) {
            throw new UnreadablePayloadException(ex.getMessage(), ex);
        }
    }
}
