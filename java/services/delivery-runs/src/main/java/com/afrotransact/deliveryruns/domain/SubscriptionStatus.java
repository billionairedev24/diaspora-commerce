package com.afrotransact.deliveryruns.domain;

/** Subscription lifecycle. */
public enum SubscriptionStatus {
    /** Email-only subscriber who has not clicked the confirmation link yet (double opt-in). */
    PENDING_CONFIRMATION,
    ACTIVE,
    /** Every channel withdrawn. */
    CANCELLED
}
