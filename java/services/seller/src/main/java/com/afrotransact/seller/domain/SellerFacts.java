package com.afrotransact.seller.domain;

import java.time.Instant;

/**
 * What a reward rule needs to know about an organisation besides the metric.
 *
 * @param paidSubscription      on a paid plan with a Stripe subscription (trialing or active), so a reward can be
 *                              applied
 * @param subscriptionStartedAt when the subscription started (its trial's first day): windows anchored at
 *                              SUBSCRIPTION_START
 * @param renewalAt             when Stripe creates the next invoice: the trial end while trialing, else the period end
 */
public record SellerFacts(String orgId, OrgKind kind, String planCode, Instant approvedAt,
                          boolean paidSubscription, Instant subscriptionStartedAt, Instant renewalAt) {

    public SellerFacts(String orgId, OrgKind kind, String planCode, Instant approvedAt, boolean paidSubscription) {
        this(orgId, kind, planCode, approvedAt, paidSubscription, null, null);
    }

    /** Where a rule's window starts for this organisation (null when it has not started). */
    public Instant anchor(RewardWindowAnchor a) {
        return a == RewardWindowAnchor.SUBSCRIPTION_START ? subscriptionStartedAt : approvedAt;
    }
}
