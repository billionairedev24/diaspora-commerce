package com.afrotransact.trustops.application;

import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.trustops.domain.Claim;
import com.afrotransact.trustops.domain.ListingFlagSource;
import com.afrotransact.trustops.domain.ModerationItem;

/**
 * Event payloads shared by the use cases and the SLA sweep, so every {@code trustops.claim.*} and
 * {@code trustops.moderation.*} event carries the same fields whoever publishes it (S237: the parties' ids on
 * escalations, the staff note on decisions).
 */
final class TrustOpsPayloads {

    private TrustOpsPayloads() {
    }

    /** {@code TrustOpsClaimEvent}: the claim, both parties, the current deadline and the staff decision note. */
    static Map<String, Object> claim(Claim c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claimId", c.id().toString());
        m.put("reference", c.reference());
        m.put("type", c.type().name());
        m.put("status", c.status().name());
        m.put("orderId", c.orderId());
        m.put("orderNumber", c.orderNumber());
        m.put("shipmentId", c.shipmentId());
        m.put("sellerOrgId", c.sellerOrgId());
        m.put("customerId", c.customerId());
        m.put("amountMinor", c.amountMinor());
        m.put("currency", c.currency());
        m.put("outcome", c.outcome() == null ? null : c.outcome().name());
        m.put("refundMinor", c.refundMinor());
        m.put("refundId", c.refundId());
        m.put("dueAt", c.sla().dueAt().toString());
        m.put("escalated", c.escalated());
        m.put("escalationReason", c.escalationReason());
        m.put("decisionNote", c.decisionNote());
        return m;
    }

    /**
     * {@code TrustOpsModerationEvent}. {@code customerId} is the shopper who reported a live listing (null for new
     * listings, staff or automated flags and reviews). Review items carry {@code bookingId}, {@code serviceId} and
     * {@code rating} instead of a product; {@code sellerOrgId} is then the reviewed provider.
     */
    static Map<String, Object> moderation(ModerationItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", i.id().toString());
        m.put("productId", i.product() == null ? null : i.product().productId().toString());
        m.put("atin", i.product() == null ? null : i.product().atin());
        m.put("sellerOrgId", i.orgId());
        m.put("customerId", i.report() != null && i.report().source() == ListingFlagSource.CUSTOMER_REPORT
                ? i.report().reporterId() : null);
        m.put("title", i.title());
        if (i.review() != null) {
            m.put("bookingId", i.review().bookingId().toString());
            m.put("serviceId", i.review().serviceId() == null ? null : i.review().serviceId().toString());
            m.put("rating", i.review().rating());
        }
        m.put("status", i.status().name());
        m.put("priority", i.priority().name());
        m.put("reasonCode", i.reasonCode() == null ? null : i.reasonCode().name());
        m.put("note", i.decisionNote());
        m.put("escalationReason", i.escalationReason());
        m.put("aiRiskLevel", i.triage() == null ? null : i.triage().riskLevel());
        m.put("itemType", i.type().name());
        m.put("reportSource", i.report() == null ? null : i.report().source().name());
        m.put("reportReason", i.report() == null ? null : i.report().reasonCode().name());
        m.put("reports", i.report() == null ? 0 : i.report().reports());
        m.put("decidedBy", i.decidedBy());
        m.put("dueAt", i.sla().dueAt().toString());
        return m;
    }
}
