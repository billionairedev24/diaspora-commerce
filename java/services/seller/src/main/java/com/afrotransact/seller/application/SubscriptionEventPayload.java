package com.afrotransact.seller.application;

import java.time.Instant;

import com.afrotransact.seller.domain.Subscription;

/**
 * Payload of seller.subscription.changed.
 *
 * @param change ACTIVATED, UPGRADED, CHANGE_SCHEDULED, CHANGE_CANCELLED, CHANGE_APPLIED, CANCEL_SCHEDULED, RESUMED,
 *               STATUS_CHANGED, PAYMENT_FAILED or PAYMENT_SUCCEEDED
 */
public record SubscriptionEventPayload(String orgId, String change, String planCode, String cadence, String status,
                                       String previousPlanCode, String previousCadence, String pendingPlanCode,
                                       String pendingCadence, Instant pendingEffectiveAt, Instant trialEndsAt,
                                       Instant currentPeriodEnd, boolean cancelAtPeriodEnd, String dunning,
                                       Instant occurredAt) {

    static SubscriptionEventPayload of(Subscription s, String change, String previousPlan, String previousCadence,
                                       Instant at) {
        return new SubscriptionEventPayload(s.orgId(), change, s.planCode(),
                s.cadence() == null ? null : s.cadence().name(), s.status().name(), previousPlan, previousCadence,
                s.pendingPlanCode(), s.pendingCadence() == null ? null : s.pendingCadence().name(),
                s.pendingEffectiveAt(), s.trialEndsAt(), s.currentPeriodEnd(), s.cancelAtPeriodEnd(),
                s.dunning().name(), at);
    }
}
