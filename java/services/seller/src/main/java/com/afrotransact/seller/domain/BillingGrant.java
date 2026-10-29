package com.afrotransact.seller.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A credit, free period, discount, waived invoice, charge gate or trial change given to an organisation, by a reward
 * rule or by staff: who, what, why and what it changed. Rule grants are unique per (rule, organisation) and staff grants
 * per request key, so a replay never grants twice.
 *
 * <p>{@code freeDays} / {@code freeMonths} push the first charge out; {@code creditCents} adds platform credit the next
 * invoices use first; {@code percentOff} is a coupon for {@code durationInvoices} invoices ({@code remainingInvoices}
 * left); {@code gateMode} (with {@code metric}, {@code metricValue} = threshold and {@code trialEnd} = until) records a
 * charge gate set on the subscription.
 */
public record BillingGrant(UUID id, String orgId, GrantSource source, UUID ruleId, String requestKey, GrantType type,
                           Long creditCents, Integer freeDays, Integer freeMonths, BigDecimal percentOff,
                           Integer durationInvoices, Integer remainingInvoices, String gateMode, String invoiceId,
                           Instant trialEnd, GrantStatus status, String reasonCode, String note, String summary,
                           String actorId, RewardMetric metric, Long metricValue, Effect effect, int attempts,
                           String lastError, Instant createdAt, Instant appliedAt, long version) {

    public BillingGrant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orgId, "orgId");
        effect = effect == null ? Effect.NONE : effect;
    }

    /**
     * What applying the grant changed.
     *
     * @param reference            a local key of the change (credit entry, coupon, waiver)
     * @param previousTrialEnd / newTrialEnd the first-charge date before and after a free-period grant
     */
    public record Effect(String reference, Instant previousTrialEnd, Instant newTrialEnd) {

        public static final Effect NONE = new Effect(null, null, null);
    }

    public BillingGrant applied(Effect e, Instant at) {
        return new BillingGrant(id, orgId, source, ruleId, requestKey, type, creditCents, freeDays, freeMonths,
                percentOff, durationInvoices, remainingInvoices, gateMode, invoiceId, trialEnd, GrantStatus.APPLIED,
                reasonCode, note, summary, actorId, metric, metricValue, e, attempts + 1, null, createdAt, at, version);
    }

    public BillingGrant failed(String error) {
        return new BillingGrant(id, orgId, source, ruleId, requestKey, type, creditCents, freeDays, freeMonths,
                percentOff, durationInvoices, remainingInvoices, gateMode, invoiceId, trialEnd, GrantStatus.FAILED,
                reasonCode, note, summary, actorId, metric, metricValue, effect, attempts + 1, error, createdAt,
                appliedAt, version);
    }
}
