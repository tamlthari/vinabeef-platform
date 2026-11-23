package com.vinabeef.security;

/** For code that only needs to know who is signed in and what they may see. */
public interface CurrentScopeProvider {

    /** The scope of the signed-in person; a person who is not signed in, or has no account, gets a scope that sees nothing. */
    UserScope getCurrentScope();
}
