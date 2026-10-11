package com.afrotransact.seller.port;

import java.time.Instant;

/**
 * Subscription fields read from Stripe Billing.
 *
 * @param priceId   the plan item's Price id (maps back to our plan version and cadence)
 * @param lookupKey the Price's lookup key, when Stripe sent it (fallback before a Price is adopted)
 */
public record SubscriptionSnapshot(String id, String customerId, String status, String itemId, String priceId,
                                   String lookupKey, Instant trialEnd, Instant currentPeriodStart,
                                   Instant currentPeriodEnd, boolean cancelAtPeriodEnd) {
}
