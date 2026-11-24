package com.vinabeef.ingestion.port;

import com.vinabeef.ingestion.SafetrustEventDto;

/** Output port: turns a stored JSON payload back into an event. */
public interface EventPayloadParser {

    /** The payload cannot be read as an event; the message is the parser's own. */
    class UnreadablePayloadException extends RuntimeException {
        public UnreadablePayloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    SafetrustEventDto parse(String payload) throws UnreadablePayloadException;
}
