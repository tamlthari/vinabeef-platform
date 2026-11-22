package com.vinabeef.web;

import java.util.Objects;

/**
 * Rules that stop an administrator from locking everybody out or reaching outside their own scope when they
 * edit or delete an account. Pure logic so the rules are tested without a database.
 */
final class UserChangeGuard {

    /** HTTP status the controller should answer with, and the sentence shown to the person. */
    record Denial(int status, String message) {
    }

    /** Who is acting. */
    record Actor(String username, String role, String farmId) {
        boolean isFarmManager() {
            return "FARM_MANAGER".equals(role);
        }
    }

    /** The account being changed, as stored today. */
    record Target(String username, String role, String farmId, boolean enabled) {
    }

    private UserChangeGuard() {
    }

    /**
     * @param newRole          role after the change (the current one when it is not being changed)
     * @param newEnabled       enabled flag after the change
     * @param enabledAdmins    how many enabled SYSTEM_ADMIN accounts exist right now
     * @return null when the change is allowed
     */
    static Denial denyChange(Actor actor, Target target, String newRole, boolean newEnabled, long enabledAdmins) {
        Denial outOfScope = outOfScope(actor, target);
        if (outOfScope != null) {
            return outOfScope;
        }
        boolean self = sameUser(actor, target);
        if (self && !Objects.equals(actor.role(), newRole)) {
            return new Denial(400, "You cannot demote or change your own active role.");
        }
        if (self && !newEnabled) {
            return new Denial(400, "You cannot disable your own account.");
        }
        if (actor.isFarmManager() && ("SYSTEM_ADMIN".equals(newRole) || "EXECUTIVE".equals(newRole))) {
            return new Denial(403, "Farm Managers cannot grant SYSTEM_ADMIN or EXECUTIVE roles.");
        }
        boolean loosesAdmin = isEnabledAdmin(target) && !("SYSTEM_ADMIN".equals(newRole) && newEnabled);
        if (loosesAdmin && enabledAdmins <= 1) {
            return new Denial(409, "This is the last enabled SYSTEM_ADMIN. Create or enable another administrator first.");
        }
        return null;
    }

    static Denial denyDelete(Actor actor, Target target, long enabledAdmins) {
        Denial outOfScope = outOfScope(actor, target);
        if (outOfScope != null) {
            return outOfScope;
        }
        if (sameUser(actor, target)) {
            return new Denial(400, "You cannot delete your own account.");
        }
        if (isEnabledAdmin(target) && enabledAdmins <= 1) {
            return new Denial(409, "This is the last enabled SYSTEM_ADMIN and cannot be deleted.");
        }
        return null;
    }

    private static Denial outOfScope(Actor actor, Target target) {
        if (actor.isFarmManager() && !Objects.equals(actor.farmId(), target.farmId())) {
            return new Denial(403, "You can only modify users assigned to your own farm (" + actor.farmId() + ").");
        }
        return null;
    }

    private static boolean sameUser(Actor actor, Target target) {
        return actor.username() != null && actor.username().equalsIgnoreCase(target.username());
    }

    private static boolean isEnabledAdmin(Target target) {
        return "SYSTEM_ADMIN".equals(target.role()) && target.enabled();
    }
}
