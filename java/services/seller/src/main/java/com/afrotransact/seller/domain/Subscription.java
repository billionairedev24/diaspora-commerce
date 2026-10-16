package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * An organisation's plan subscription. Downgrades and cadence changes are recorded as a pending change applied at
 * {@code pendingEffectiveAt} (the renewal); upgrades prorate immediately.  {@code planVersion} is the plan version
 * (prices) the organisation subscribed to; it changes only by a plan change or a staff migration.
 *
 * <p>Billing periods are computed locally from {@code anchorDate} using RFC 5545 rrule ({@link BillingScheduler}):
 * no Stripe Billing subscription or webhook is needed for scheduling.
 *
 * <p>{@code stripeCustomerId} is kept only for Stripe Connect (payout-bank compliance) references; it is no longer
 * used by the billing subscription lifecycle.
 */
public record Subscription(String orgId, String planCode, int planVersion, Cadence cadence, SubscriptionStatus status,
                           PaymentMethodKind paymentMethodKind,
                           Instant anchorDate,
                           Instant trialEndsAt,
                           Instant currentPeriodStart, Instant currentPeriodEnd, boolean cancelAtPeriodEnd,
                           String pendingPlanCode, Cadence pendingCadence, Instant pendingEffectiveAt,
                           DunningState dunning, int failedPaymentAttempts, Instant nextPaymentAttemptAt,
                           Instant updatedAt, long version) {

    public Subscription {
        Objects.requireNonNull(orgId, "orgId");
        dunning = dunning == null ? DunningState.NONE : dunning;
    }

    public static Subscription selected(String orgId, String planCode, int planVersion, Cadence cadence,
                                        PaymentMethodKind pm, Instant now) {
        return new Subscription(orgId, planCode, planVersion, cadence, SubscriptionStatus.PENDING_ACTIVATION, pm,
                null, null, null, null, false, null, null, null, DunningState.NONE, 0, null, now, 0);
    }

    public boolean hasPendingChange() {
        return pendingPlanCode != null;
    }

    /** When a change "at renewal" takes effect: the trial end while trialing, else the period end. */
    public Instant renewalAt() {
        if (status == SubscriptionStatus.TRIALING && trialEndsAt != null) {
            return trialEndsAt;
        }
        return currentPeriodEnd;
    }

    /**
     * Sets the period fields from a locally computed billing cycle (anchor-based rrule), activating the subscription.
     * Call this when the first billing period is established (after approval or trial end).
     */
    public Subscription withPeriod(Instant anchor, SubscriptionStatus s,
                                   Instant trialEnd, Instant periodStart, Instant periodEnd, boolean cancelAtEnd,
                                   Instant now) {
        return new Subscription(orgId, planCode, planVersion, cadence, s, paymentMethodKind,
                anchor, trialEnd, periodStart, periodEnd, cancelAtEnd,
                pendingPlanCode, pendingCadence, pendingEffectiveAt,
                dunning, failedPaymentAttempts, nextPaymentAttemptAt, now, version);
    }

    public Subscription withPlan(String code, int v, Cadence c, Instant now) {
        return new Subscription(orgId, code, v, c, status, paymentMethodKind,
                anchorDate, trialEndsAt, currentPeriodStart, currentPeriodEnd, cancelAtPeriodEnd,
                null, null, null,
                dunning, failedPaymentAttempts, nextPaymentAttemptAt, now, version);
    }

    public Subscription withPending(String code, Cadence c, Instant effectiveAt, Instant now) {
        return new Subscription(orgId, planCode, planVersion, cadence, status, paymentMethodKind,
                anchorDate, trialEndsAt, currentPeriodStart, currentPeriodEnd, cancelAtPeriodEnd,
                code, c, effectiveAt, dunning, failedPaymentAttempts, nextPaymentAttemptAt, now, version);
    }

    public Subscription withCancelAtPeriodEnd(boolean cancel, Instant now) {
        return new Subscription(orgId, planCode, planVersion, cadence, status, paymentMethodKind,
                anchorDate, trialEndsAt, currentPeriodStart, currentPeriodEnd, cancel,
                pendingPlanCode, pendingCadence, pendingEffectiveAt, dunning, failedPaymentAttempts,
                nextPaymentAttemptAt, now, version);
    }

    public Subscription withDunning(DunningState d, SubscriptionStatus s, int attempts, Instant nextAttempt,
                                    Instant now) {
        return new Subscription(orgId, planCode, planVersion, cadence, s, paymentMethodKind,
                anchorDate, trialEndsAt, currentPeriodStart, currentPeriodEnd,
                cancelAtPeriodEnd, pendingPlanCode, pendingCadence, pendingEffectiveAt, d, attempts, nextAttempt, now,
                version);
    }

    /** Rolls the period forward by one cadence occurrence (renewal). */
    public Subscription withNextPeriod(Instant nextStart, Instant nextEnd, Instant now) {
        return new Subscription(orgId, planCode, planVersion, cadence, SubscriptionStatus.ACTIVE, paymentMethodKind,
                anchorDate, null, nextStart, nextEnd, cancelAtPeriodEnd,
                pendingPlanCode, pendingCadence, pendingEffectiveAt, DunningState.NONE, 0, null, now, version);
    }
}
