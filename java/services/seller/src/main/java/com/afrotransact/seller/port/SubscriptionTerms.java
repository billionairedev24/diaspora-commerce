package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.seller.domain.PlanFeeCollection;
import com.afrotransact.seller.domain.TrialSource;

/**
 * Billing terms kept next to a subscription and changed by their own use cases (no optimistic-lock contention with
 * Stripe webhooks): the per-seller trial override, the redeemed campaign, the mirrored credit balance and how the
 * organisation pays its plan (S298).
 *
 * @param planFeeCollectionSource DEFAULT, ONBOARDING, SELLER or STAFF
 */
public record SubscriptionTerms(String orgId, Integer trialDaysOverride, String trialOverrideReason,
                                String trialOverrideNote, String trialOverrideBy, UUID campaignId,
                                TrialSource trialSource, long creditBalanceCents,
                                PlanFeeCollection planFeeCollection, String planFeeCollectionSource,
                                Instant planFeeCollectionChangedAt, String planFeeCollectionChangedBy) {

    public static SubscriptionTerms none(String orgId) {
        return new SubscriptionTerms(orgId, null, null, null, null, null, null, 0, PlanFeeCollection.CARD, "DEFAULT",
                null, null);
    }
}
