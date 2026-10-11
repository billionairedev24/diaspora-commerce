package com.afrotransact.seller.domain;

import java.time.Instant;

/**
 * What a reward rule needs to know about an organisation besides the metric.
 *
 * @param paidSubscription on a paid plan with a Stripe subscription (trialing or active), so a reward can be applied
 */
public record SellerFacts(String orgId, OrgKind kind, String planCode, Instant approvedAt,
                          boolean paidSubscription) {
}
