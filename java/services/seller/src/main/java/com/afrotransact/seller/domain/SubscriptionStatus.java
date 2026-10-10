package com.afrotransact.seller.domain;

import java.util.Locale;

/** Subscription states (Stripe's, plus PENDING_ACTIVATION until the Stripe subscription exists). */
public enum SubscriptionStatus {
    PENDING_ACTIVATION,
    TRIALING,
    ACTIVE,
    PAST_DUE,
    UNPAID,
    INCOMPLETE,
    PAUSED,
    CANCELED;

    public static SubscriptionStatus fromStripe(String raw) {
        if (raw == null) {
            return INCOMPLETE;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "trialing" -> TRIALING;
            case "active" -> ACTIVE;
            case "past_due" -> PAST_DUE;
            case "unpaid" -> UNPAID;
            case "paused" -> PAUSED;
            case "canceled", "incomplete_expired" -> CANCELED;
            default -> INCOMPLETE;
        };
    }
}
