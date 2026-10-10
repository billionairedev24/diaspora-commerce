package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A dispute / A-to-z guarantee claim on an order or shipment. The seller has a response window (SLA), then staff
 * decide with a refund (through payments-service) or a denial. Transitions follow {@link ClaimStatus#next()}.
 */
public record Claim(UUID id, String reference, ClaimType type, ClaimStatus status, String orderId, String orderNumber,
                    String shipmentId, String sellerOrgId, String customerId, long amountMinor, String currency,
                    String description, List<Evidence> evidence, String sellerResponse, Instant sellerRespondedAt,
                    Sla sla, boolean escalated, String escalationReason, ClaimOutcome outcome, Long refundMinor,
                    String refundId, String decidedBy, Instant decidedAt, String decisionNote, Instant openedAt,
                    Instant updatedAt, long version) {

    public static final int MAX_EVIDENCE = 20;

    public Claim {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sla, "sla");
        Objects.requireNonNull(openedAt, "openedAt");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static Claim open(UUID id, String reference, ClaimDraft d, Instant now, SlaPolicy policy) {
        if (d.type() == null) {
            throw new TrustRuleViolation("trustops.claim-type-required", "Pick what went wrong",
                    Map.of("type", "Required"));
        }
        String orderId = Texts.required(d.orderId(), "orderId", "Order", 80);
        String seller = Texts.required(d.sellerOrgId(), "sellerOrgId", "Seller", 80);
        String customer = Texts.required(d.customerId(), "customerId", "Customer", 120);
        if (d.amountMinor() <= 0) {
            throw new TrustRuleViolation("trustops.claim-amount", "The claimed amount must be positive",
                    Map.of("amountMinor", "Must be greater than 0"));
        }
        String currency = Texts.required(d.currency(), "currency", "Currency", 3).toUpperCase(Locale.ROOT);
        if (currency.length() != 3) {
            throw new TrustRuleViolation("trustops.claim-currency", "Currency is a 3-letter ISO code",
                    Map.of("currency", "ISO 4217 code"));
        }
        String description = Texts.required(d.description(), "description", "Description", 4000);
        List<Evidence> ev = new ArrayList<>();
        for (String asset : d.evidenceAssetIds()) {
            ev = withEvidence(ev, new Evidence(asset(asset), PartyRole.CUSTOMER, customer, null, now));
        }
        return new Claim(id, reference, d.type(), ClaimStatus.AWAITING_SELLER, orderId,
                Texts.optional(d.orderNumber(), "orderNumber", "Order number", 40),
                Texts.optional(d.shipmentId(), "shipmentId", "Shipment", 80), seller, customer, d.amountMinor(),
                currency, description, ev, null, null, policy.sellerResponse(now), false, null, null, null, null, null,
                null, null, now, now, 0);
    }

    /** Adds an evidence asset while the claim is open (duplicates are ignored). */
    public Claim addEvidence(PartyRole party, String by, String assetId, String note, Instant now) {
        requireOpen();
        List<Evidence> ev = withEvidence(evidence, new Evidence(asset(assetId), party, by,
                Texts.optional(note, "note", "Note", 500), now));
        return copy(status, ev, sellerResponse, sellerRespondedAt, sla, escalated, escalationReason, now);
    }

    /** The seller answers inside the response window; the claim moves to staff review with a decision timer. */
    public Claim sellerRespond(String by, String message, List<String> assetIds, Instant now, SlaPolicy policy) {
        move(ClaimStatus.UNDER_REVIEW, ClaimStatus.AWAITING_SELLER);
        String text = Texts.required(message, "message", "Response", 4000);
        List<Evidence> ev = evidence;
        for (String asset : assetIds == null ? List.<String>of() : assetIds) {
            ev = withEvidence(ev, new Evidence(asset(asset), PartyRole.SELLER, by, null, now));
        }
        return copy(ClaimStatus.UNDER_REVIEW, ev, text, now, policy.claimDecision(now), escalated, escalationReason,
                now);
    }

    /** The seller's window passed without an answer: staff review starts without one. */
    public Claim sellerWindowLapsed(Instant now, SlaPolicy policy) {
        move(ClaimStatus.UNDER_REVIEW, ClaimStatus.AWAITING_SELLER);
        if (!sla.breached(now)) {
            throw new IllegalStateException("The seller's response window is still open until " + sla.dueAt());
        }
        return copy(ClaimStatus.UNDER_REVIEW, evidence, null, null, policy.claimDecision(now), escalated,
                escalationReason, now);
    }

    public Claim escalate(String reason, Instant now, SlaPolicy policy) {
        move(ClaimStatus.ESCALATED, ClaimStatus.AWAITING_SELLER, ClaimStatus.UNDER_REVIEW);
        return copy(ClaimStatus.ESCALATED, evidence, sellerResponse, sellerRespondedAt, policy.claimEscalated(now),
                true, Texts.required(reason, "reason", "Escalation reason", 1000), now);
    }

    /** Refund decision; {@code refundId} is payments-service's refund id. */
    public Claim decideRefund(String by, long refund, String paymentsRefundId, String note, Instant now) {
        move(ClaimStatus.RESOLVED_REFUND, ClaimStatus.UNDER_REVIEW, ClaimStatus.ESCALATED);
        requireRefundAmount(refund);
        return decided(ClaimStatus.RESOLVED_REFUND, ClaimOutcome.REFUND, refund, paymentsRefundId, by,
                Texts.optional(note, "note", "Decision note", 2000), now);
    }

    public Claim deny(String by, String note, Instant now) {
        move(ClaimStatus.RESOLVED_DENIED, ClaimStatus.UNDER_REVIEW, ClaimStatus.ESCALATED);
        return decided(ClaimStatus.RESOLVED_DENIED, ClaimOutcome.DENY, null, null, by,
                Texts.required(note, "note", "Reason for the denial", 2000), now);
    }

    public Claim withdraw(String by, Instant now) {
        move(ClaimStatus.WITHDRAWN, ClaimStatus.AWAITING_SELLER, ClaimStatus.UNDER_REVIEW, ClaimStatus.ESCALATED);
        return decided(ClaimStatus.WITHDRAWN, null, null, null, by, "Withdrawn by the customer", now);
    }

    /** Checks a refund amount against the claim before payments is called. */
    public void requireRefundAmount(long refund) {
        if (refund <= 0 || refund > amountMinor) {
            throw new TrustRuleViolation("trustops.refund-amount", "The refund must be between 1 and the claimed "
                    + amountMinor + " " + currency, Map.of("refund", "1–" + amountMinor));
        }
    }

    public boolean open() {
        return status.open();
    }

    private void move(ClaimStatus target, ClaimStatus... from) {
        if (!status.canMoveTo(target) || !List.of(from).contains(status)) {
            throw new IllegalStateException("Claim " + reference + " cannot go from " + status + " to " + target);
        }
    }

    private void requireOpen() {
        if (!open()) {
            throw new IllegalStateException("Claim " + reference + " is closed (" + status + ")");
        }
    }

    private static String asset(String assetId) {
        return Texts.required(assetId, "assetId", "Evidence asset id", 120);
    }

    private static List<Evidence> withEvidence(List<Evidence> current, Evidence e) {
        if (current.stream().anyMatch(x -> x.assetId().equals(e.assetId()))) {
            return current;
        }
        if (current.size() >= MAX_EVIDENCE) {
            throw new TrustRuleViolation("trustops.too-much-evidence", "A claim holds at most " + MAX_EVIDENCE
                    + " evidence files", Map.of("assetId", "Limit reached"));
        }
        List<Evidence> out = new ArrayList<>(current);
        out.add(e);
        return out;
    }

    private Claim copy(ClaimStatus s, List<Evidence> ev, String response, Instant respondedAt, Sla timer,
                       boolean esc, String escReason, Instant now) {
        return new Claim(id, reference, type, s, orderId, orderNumber, shipmentId, sellerOrgId, customerId,
                amountMinor, currency, description, ev, response, respondedAt, timer, esc, escReason, outcome,
                refundMinor, refundId, decidedBy, decidedAt, decisionNote, openedAt, now, version);
    }

    private Claim decided(ClaimStatus s, ClaimOutcome o, Long refund, String paymentsRefundId, String by, String note,
                          Instant now) {
        return new Claim(id, reference, type, s, orderId, orderNumber, shipmentId, sellerOrgId, customerId,
                amountMinor, currency, description, evidence, sellerResponse, sellerRespondedAt, sla, escalated,
                escalationReason, o, refund, paymentsRefundId, by, now, note, openedAt, now, version);
    }
}
