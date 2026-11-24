package com.vinabeef.security.port;

import java.util.List;
import java.util.Optional;

/** Output port: the facts a person's scope is made of. */
public interface ScopeDirectory {

    /** A person's account as far as scope goes. {@code farmId} may be null. */
    record Account(String username, String role, String farmId) {
    }

    Optional<Account> findAccount(String username);

    /** The barns a barn supervisor is assigned to, in barn id order. */
    List<String> barnsAssignedTo(String username);

    /** The farm a barn belongs to; empty when the barn does not exist. */
    Optional<String> farmOfBarn(String barnId);
}
