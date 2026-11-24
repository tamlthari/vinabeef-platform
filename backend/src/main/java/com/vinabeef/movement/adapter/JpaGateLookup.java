package com.vinabeef.movement.adapter;

import com.vinabeef.facilities.Gate;
import com.vinabeef.facilities.GateRepository;
import com.vinabeef.movement.port.GateLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@link GateLookup} over the Spring Data repository of gates. */
@Component
@RequiredArgsConstructor
public class JpaGateLookup implements GateLookup {

    private final GateRepository gates;

    @Override
    public Optional<Gate> byId(String gateId) {
        return gates.findById(gateId);
    }

    @Override
    public Optional<Gate> activeByReaderName(String readerName) {
        return gates.findByReaderNameAndRetiredAtIsNull(readerName);
    }
}
