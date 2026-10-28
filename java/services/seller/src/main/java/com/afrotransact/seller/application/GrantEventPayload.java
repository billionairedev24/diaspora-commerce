package com.afrotransact.seller.application;

import java.math.BigDecimal;
import java.time.Instant;

import com.afrotransact.seller.domain.BillingGrant;

/**
 * Payload of seller.billing.grant_applied (notifications tells the organisation's owners).
 *
 * @param summary     "1 month free", "$25 credit", "20% off your next 3 invoices"
 * @param reason      the rule's name for a reward, else null
 * @param trialEndsAt the subscription's trial end after the grant (free days), else null
 */
public record GrantEventPayload(String orgId, String grantId, String source, String type, String summary,
                                String reason, Long creditCents, Integer freeDays, BigDecimal percentOff,
                                Integer durationInvoices, Instant trialEndsAt, Instant occurredAt) {

    static GrantEventPayload of(BillingGrant g, Instant trialEndsAt, Instant at) {
        return new GrantEventPayload(g.orgId(), g.id().toString(), g.source().name(), g.type().name(), g.summary(),
                g.ruleId() == null ? null : g.note(), g.creditCents(), g.freeDays(), g.percentOff(),
                g.durationInvoices(), trialEndsAt, at);
    }
}
