package com.vinabeef.movement.port;

import com.vinabeef.facilities.Gate;

import java.util.Optional;

/** Output port: finds a gate by id, or by the name of the reader that is still active on it. */
public interface GateLookup {

    Optional<Gate> byId(String gateId);

    /** Retired gates keep their reader name for history, so only the active gate counts. */
    Optional<Gate> activeByReaderName(String readerName);
}
