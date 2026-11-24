package com.vinabeef.security.port;

import java.util.Optional;

/** Output port: who is signed in for the request being handled. */
public interface AuthenticatedUser {

    /** The username of the signed-in person; empty when nobody is signed in (anonymous counts as nobody). */
    Optional<String> username();
}
