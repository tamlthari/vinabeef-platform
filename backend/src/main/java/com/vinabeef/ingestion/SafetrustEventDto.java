package com.vinabeef.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SafetrustEventDto {
    @JsonProperty("EVENT_KEY")
    private Long eventKey;

    @JsonProperty("TRANSACTION_ID")
    private Long transactionId;

    @JsonProperty("DEVICE_KEY")
    private String deviceKey;

    @JsonProperty("COMPANY_KEY")
    private String companyKey;

    @JsonProperty("READER_NAME")
    private String readerName;

    @JsonProperty("CARD_UUID")
    private String cardUuid;

    @JsonProperty("CREDENTIAL_IDENTIFIER")
    private String credentialIdentifier;

    @JsonProperty("TIMESTAMP")
    private Long timestamp;

    @JsonProperty("NOTES")
    private Map<String, Object> notes;

    @JsonProperty("rawPayload")
    private String rawPayload;

    public String getUserName() {
        if (notes != null && notes.containsKey("USER_NAME")) {
            return String.valueOf(notes.get("USER_NAME"));
        }
        return null;
    }

    public String getGateAction() {
        if (notes != null && notes.containsKey("ACTION")) {
            return String.valueOf(notes.get("ACTION"));
        }
        return "PASSAGE";
    }
}