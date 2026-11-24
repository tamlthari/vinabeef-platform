package com.vinabeef.ingestion.adapter;

import com.vinabeef.ingestion.SafetrustEventDto;
import com.vinabeef.ingestion.port.EventPayloadParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** {@link EventPayloadParser} with the application's Jackson mapper. */
@Component
@RequiredArgsConstructor
public class JacksonEventPayloadParser implements EventPayloadParser {

    private final ObjectMapper objectMapper;

    @Override
    public SafetrustEventDto parse(String payload) {
        try {
            return objectMapper.readValue(payload, SafetrustEventDto.class);
        } catch (Exception ex) {
            throw new UnreadablePayloadException(ex.getMessage(), ex);
        }
    }
}
