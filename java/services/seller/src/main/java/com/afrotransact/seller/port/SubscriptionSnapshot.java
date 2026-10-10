package com.afrotransact.seller.port;

import java.time.Instant;

/** Subscription fields read from Stripe Billing. */
public record SubscriptionSnapshot(String id, String customerId, String status, String itemId, String lookupKey,
                                   Instant trialEnd, Instant currentPeriodStart, Instant currentPeriodEnd,
                                   boolean cancelAtPeriodEnd) {
}
