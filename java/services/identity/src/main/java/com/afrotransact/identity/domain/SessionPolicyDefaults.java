package com.afrotransact.identity.domain;

/**
 * The built-in sign-out policy per audience. The gateway-bff carries the same table as its fail-safe when identity
 * cannot be reached, so change both together.
 */
public final class SessionPolicyDefaults {

    private static final int HOUR = 60;
    private static final int DAY = 24 * HOUR;

    private SessionPolicyDefaults() {
    }

    public static SessionPolicy of(SessionAudience audience) {
        return switch (audience) {
            case STAFF -> new SessionPolicy(15, 8 * HOUR, 60);
            case SELLER -> new SessionPolicy(30, 12 * HOUR, 60);
            case CUSTOMER_WEB, CUSTOMER_APP -> new SessionPolicy(30 * DAY, 90 * DAY, 0);
            case DRIVER -> new SessionPolicy(14 * HOUR, 14 * HOUR, 0);
        };
    }
}
