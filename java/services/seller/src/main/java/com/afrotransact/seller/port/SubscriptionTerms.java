package com.afrotransact.seller.port;

import java.util.UUID;

import com.afrotransact.seller.domain.TrialSource;

/**
 * Billing terms kept next to a subscription and changed by their own use cases (no optimistic-lock contention with
 * Stripe webhooks): the per-seller trial override, the redeemed campaign, the mirrored credit balance and the
 * collection-from-earnings opt-in.
 */
public record SubscriptionTerms(String orgId, Integer trialDaysOverride, String trialOverrideReason,
                                String trialOverrideNote, String trialOverrideBy, UUID campaignId,
                                TrialSource trialSource, long creditBalanceCents, boolean collectFromEarnings) {

    public static SubscriptionTerms none(String orgId) {
        return new SubscriptionTerms(orgId, null, null, null, null, null, null, 0, false);
    }
}
