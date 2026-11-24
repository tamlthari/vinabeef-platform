package com.vinabeef.movement.port;

/** Output port: the alerts the movement use case raises or closes. */
public interface AlertSink {

    void raiseUnexpectedTag(String barnId, String tagEpc, String userName, String readerName, String gateId, long eventKey);

    void raiseUnmappedCredential(String barnId, String credential, String readerName, String gateId, long eventKey);

    void raiseWrongBarn(String animalId, String assignedBarn, String detectedBarn, String readerName, String direction);

    void raiseUnregisteredReader(String readerName, String cardUuid, long eventKey);

    /** An animal that came back IN ends its open MISSING_CATTLE alert. */
    void resolveMissingCattle(String animalId, String readerName);
}
