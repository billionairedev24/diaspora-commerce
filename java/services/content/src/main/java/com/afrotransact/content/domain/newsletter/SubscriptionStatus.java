package com.afrotransact.content.domain.newsletter;

/** Double opt-in: PENDING until the emailed link is followed, then CONFIRMED. */
public enum SubscriptionStatus {
    PENDING,
    CONFIRMED,
    UNSUBSCRIBED
}
