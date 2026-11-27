package com.vinabeef.web;

import com.vinabeef.security.UserScope;

/**
 * Which farm a disease-surveillance request is about, decided from who is asking and what they asked for.
 *
 * <p>The rule, in one place so every endpoint (overview, export, transmit, transmission detail and body) applies the same one:
 * <ul>
 *   <li>SYSTEM_ADMIN and EXECUTIVE may ask for one farm or for {@code ALL}.</li>
 *   <li>A FARM_MANAGER always gets their own farm. Asking for {@code ALL} means "everything I may see", which is the own farm;
 *       asking for another farm is refused (it used to be answered with the own farm, hiding a tampered request).</li>
 *   <li>A FARM_MANAGER with no farm on the account is refused. This used to fall through to {@code ALL}, so one account
 *       without a farm could read every farm's surveillance data.</li>
 *   <li>Any other role is refused.</li>
 * </ul>
 */
final class SurveillanceFarmScope {

    static final String ALL = "ALL";

    /** The request is outside what the account may see. Mapped to HTTP 403 by the controller. */
    static final class Denied extends RuntimeException {
        Denied(String message) {
            super(message);
        }
    }

    private SurveillanceFarmScope() {
    }

    /** The farm id to filter by, or {@code ALL} for a global account that asked for every farm. */
    static String resolve(UserScope scope, String requestedFarm) {
        String asked = requestedFarm == null || requestedFarm.isBlank() ? ALL : requestedFarm.trim();
        if (scope.isGlobal()) {
            return asked;
        }
        if (scope.isFarmScoped()) {
            String own = scope.farmId();
            if (own == null || own.isBlank() || ALL.equalsIgnoreCase(own.trim())) {
                throw new Denied("Account '" + scope.username() + "' is a farm manager but is not assigned to a farm, "
                        + "so no surveillance data can be shown. Ask a system administrator to assign a farm.");
            }
            if (!ALL.equalsIgnoreCase(asked) && !own.equals(asked)) {
                throw new Denied("Farm '" + asked + "' is outside the scope of account '" + scope.username()
                        + "', which is limited to farm '" + own + "'.");
            }
            return own;
        }
        throw new Denied("Account '" + scope.username() + "' is not authorized to use disease surveillance.");
    }
}
