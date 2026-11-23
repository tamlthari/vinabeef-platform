package com.vinabeef.security;

/** For code that only needs to ask whether a person may act on one barn. */
public interface BarnAccessPolicy {

    /** True when {@code scope} may see and change the barn with this id. */
    boolean canAccessBarnId(UserScope scope, String barnId);
}
