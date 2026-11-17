package com.vinabeef.cattle;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "rfid_tags")
@Getter
@Setter
public class RfidTag {
    @Id
    @Column(name = "tag_id")
    private String tagId;

    @Column(name = "epc_value", unique = true, nullable = false)
    private String epcValue;

    @Column(name = "credential_identifier")
    private String credentialIdentifier;

    @Column(nullable = false)
    private String status;
}