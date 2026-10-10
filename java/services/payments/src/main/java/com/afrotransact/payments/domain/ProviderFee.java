package com.afrotransact.payments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A fee owed by a provider organisation, today the late provider-cancellation fee recorded by bookings-service
 * (booking.cancelled with cancelledBy = PROVIDER under 24 h). One per booking.
 *
 * @param bookingReference booking reference (BK-…); used as the journals' order id
 * @param reason           LATE_PROVIDER_CANCELLATION
 * @param stripeDebitId    Stripe account debit (py_…) once collected
 * @param debitSeq         idempotency-key suffix of the account debit; changes only after a definitive refusal
 * @param nextAttemptAt    when an OUTSTANDING fee is retried
 */
public record ProviderFee(UUID id, String bookingId, String bookingReference, String orgId, Money amount,
                          String reason, ProviderFeeStatus status, String stripeDebitId, int attempts,
                          int debitSeq, String lastError, Instant nextAttemptAt, UUID sourceEventId, Instant occurredAt,
                          Instant createdAt, Instant collectedAt, long version) {

    public static final String LATE_PROVIDER_CANCELLATION = "LATE_PROVIDER_CANCELLATION";

    private static final Duration MAX_BACKOFF = Duration.ofHours(24);

    public ProviderFee {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        if (!amount.isPositive()) {
            throw new PaymentsRuleViolation("payments.invalid-fee", "A provider fee must be positive");
        }
    }

    /** A newly recorded fee, due for collection now. */
    public static ProviderFee record(UUID id, String bookingId, String bookingReference, String orgId, Money amount,
                                     String reason, UUID sourceEventId, Instant occurredAt, Instant now) {
        return new ProviderFee(id, bookingId, bookingReference == null ? bookingId : bookingReference, orgId, amount,
                reason, ProviderFeeStatus.OUTSTANDING, null, 0, 0, null, now, sourceEventId,
                occurredAt == null ? now : occurredAt, now, null, 0);
    }

    public ProviderFee collected(String debitId, Instant now) {
        return new ProviderFee(id, bookingId, bookingReference, orgId, amount, reason, ProviderFeeStatus.COLLECTED,
                debitId, attempts + 1, debitSeq, null, null, sourceEventId, occurredAt, createdAt, now, version);
    }

    /** Stripe idempotency key of the next account debit attempt. */
    public String debitKey() {
        return "provider-fee:" + id + ":" + debitSeq;
    }

    /**
     * A failed collection attempt: retried with exponential backoff (5 min doubling, at most daily).
     *
     * @param definitive Stripe refused the request (4xx, e.g. insufficient balance): the next attempt is a new
     *                   request with a new idempotency key; otherwise (timeout, 5xx) the same request is replayed
     */
    public ProviderFee attemptFailed(String error, boolean definitive, Instant now) {
        int next = attempts + 1;
        Duration wait = Duration.ofMinutes(5).multipliedBy(1L << Math.min(next - 1, 12));
        if (wait.compareTo(MAX_BACKOFF) > 0) {
            wait = MAX_BACKOFF;
        }
        return new ProviderFee(id, bookingId, bookingReference, orgId, amount, reason, status, stripeDebitId, next,
                definitive ? debitSeq + 1 : debitSeq, error, now.plus(wait), sourceEventId, occurredAt, createdAt,
                collectedAt, version);
    }
}
