package com.vinabeef.security.port;

import java.util.Collection;
import java.util.Optional;

/** Output port: the account facts the farm-assignment rule needs. */
public interface AccountLookup {

    record AccountView(String username, String role, boolean enabled, Collection<String> assignedFarmIds) {
    }

    Optional<AccountView> byUsername(String username);
}
