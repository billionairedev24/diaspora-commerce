package com.afrotransact.seller.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A credit, free period, discount, waived invoice or trial change given to an organisation, by a reward rule or by
 * staff: who, what, why and the Stripe objects it produced. Rule grants are unique per (rule, organisation) and staff
 * grants per request key, so a replay never grants twice.
 */
public record BillingGrant(UUID id, String orgId, GrantSource source, UUID ruleId, String requestKey, GrantType type,
                           Long creditCents, Integer freeDays, BigDecimal percentOff, Integer durationInvoices,
                           String invoiceId, Instant trialEnd, GrantStatus status, String reasonCode, String note,
                           String summary, String actorId, RewardMetric metric, Long metricValue,
                           StripeRefs stripe, int attempts, String lastError, Instant createdAt, Instant appliedAt,
                           long version) {

    public BillingGrant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orgId, "orgId");
        stripe = stripe == null ? StripeRefs.NONE : stripe;
    }

    /**
     * Stripe objects a grant created or changed.
     *
     * @param previousTrialEnd / newTrialEnd the subscription's trial_end before and after a free-days grant
     */
    public record StripeRefs(String customerId, String subscriptionId, String balanceTransactionId, String couponId,
                             Instant previousTrialEnd, Instant newTrialEnd) {

        public static final StripeRefs NONE = new StripeRefs(null, null, null, null, null, null);
    }

    public BillingGrant applied(StripeRefs refs, Instant at) {
        return new BillingGrant(id, orgId, source, ruleId, requestKey, type, creditCents, freeDays, percentOff,
                durationInvoices, invoiceId, trialEnd, GrantStatus.APPLIED, reasonCode, note, summary, actorId, metric,
                metricValue, refs, attempts + 1, null, createdAt, at, version);
    }

    public BillingGrant failed(String error) {
        return new BillingGrant(id, orgId, source, ruleId, requestKey, type, creditCents, freeDays, percentOff,
                durationInvoices, invoiceId, trialEnd, GrantStatus.FAILED, reasonCode, note, summary, actorId, metric,
                metricValue, stripe, attempts + 1, error, createdAt, appliedAt, version);
    }

    /** Stripe idempotency key of this grant's call {@code step} ("balance", "coupon", "discount", "trial", "void"). */
    public String stripeKey(String step) {
        return "grant-" + id + "-" + step;
    }
}
