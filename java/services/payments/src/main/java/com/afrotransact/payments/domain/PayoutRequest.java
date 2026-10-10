package com.afrotransact.payments.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** A seller's / provider's request to be paid out from their connected-account balance (manual payouts). */
public record PayoutRequest(UUID id, String orgId, Money amount, PayoutStatus status, String requestedBy,
                            Instant requestedAt, LocalDate requestDay, String decidedBy, Instant decidedAt,
                            String holdReason, String stripePayoutId, String failureMessage, long version) {

    public PayoutRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
    }

    public static PayoutRequest request(UUID id, String orgId, Money amount, String by, Instant now, LocalDate day) {
        return new PayoutRequest(id, orgId, amount, PayoutStatus.REQUESTED, by, now, day, null, null, null, null,
                null, 0);
    }

    public PayoutRequest approve(String by, Instant now, String stripePayoutId) {
        require(PayoutStatus.REQUESTED, "approved");
        return with(PayoutStatus.IN_TRANSIT, by, now, holdReason, stripePayoutId, null);
    }

    public PayoutRequest hold(String by, Instant now, String reason) {
        require(PayoutStatus.REQUESTED, "held");
        return with(PayoutStatus.HELD, by, now, reason, stripePayoutId, null);
    }

    public PayoutRequest release(String by, Instant now) {
        require(PayoutStatus.HELD, "released");
        return with(PayoutStatus.REQUESTED, by, now, null, stripePayoutId, null);
    }

    public PayoutRequest paid() {
        if (status == PayoutStatus.PAID) {
            return this;
        }
        require(PayoutStatus.IN_TRANSIT, "marked paid");
        return with(PayoutStatus.PAID, decidedBy, decidedAt, holdReason, stripePayoutId, null);
    }

    public PayoutRequest failed(String message) {
        if (status == PayoutStatus.FAILED) {
            return this;
        }
        require(PayoutStatus.IN_TRANSIT, "marked failed");
        return with(PayoutStatus.FAILED, decidedBy, decidedAt, holdReason, stripePayoutId, message);
    }

    private void require(PayoutStatus expected, String action) {
        if (status != expected) {
            throw new PaymentsStateConflict("payments.payout-invalid-state", "Payout request " + id + " is "
                    + status + " and cannot be " + action);
        }
    }

    private PayoutRequest with(PayoutStatus s, String by, Instant at, String reason, String payoutId, String failure) {
        return new PayoutRequest(id, orgId, amount, s, requestedBy, requestedAt, requestDay, by, at, reason, payoutId,
                failure, version);
    }
}
