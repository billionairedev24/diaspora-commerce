package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to charge the membership fee on the member's saved card, and what became of it.
 *
 * @param attempt 1 for the first try of a period, then the retries
 */
public record MembershipCharge(UUID id, UUID membershipId, String customerId, String tierCode, Kind kind, int attempt,
                               Money amount, Status status, String paymentIntentId, String chargeId,
                               String refundId, String failureCode, String failureMessage, Instant periodStart,
                               Instant periodEnd, String idempotencyKey, long refundedMinor, String refundReason,
                               String refundedBy, Instant refundedAt, Instant createdAt) {

    /** Why the member was charged. */
    public enum Kind { JOIN, RENEWAL, UPGRADE }

    /** What became of the charge. */
    public enum Status { SUCCEEDED, FAILED, REFUNDED }

    public static MembershipCharge succeeded(UUID id, Membership m, Kind kind, int attempt, Money amount,
                                             String intentId, String chargeId, Instant periodStart, Instant periodEnd,
                                             String key, Instant now) {
        return new MembershipCharge(id, m.id(), m.customerId(), m.tierCode(), kind, attempt, amount, Status.SUCCEEDED,
                intentId, chargeId, null, null, null, periodStart, periodEnd, key, 0, null, null, null, now);
    }

    public static MembershipCharge failed(UUID id, Membership m, Kind kind, int attempt, Money amount,
                                          String intentId, String code, String message, Instant periodStart,
                                          Instant periodEnd, String key, Instant now) {
        return new MembershipCharge(id, m.id(), m.customerId(), m.tierCode(), kind, attempt, amount, Status.FAILED,
                intentId, null, null, code, message, periodStart, periodEnd, key, 0, null, null, null, now);
    }

    /** The whole fee given back to the card. */
    public MembershipCharge refunded(String refundId, String reason, String by, Instant now) {
        return new MembershipCharge(id, membershipId, customerId, tierCode, kind, attempt, amount, Status.REFUNDED,
                paymentIntentId, chargeId, refundId, failureCode, failureMessage, periodStart, periodEnd,
                idempotencyKey, amount.minor(), reason, by, now, createdAt);
    }
}
