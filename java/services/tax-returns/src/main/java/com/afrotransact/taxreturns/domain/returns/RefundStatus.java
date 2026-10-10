package com.afrotransact.taxreturns.domain.returns;

/** Status of a refund sent to payments-service. */
public enum RefundStatus {
    /** Recorded; the payments call has not succeeded yet (retried with the same Idempotency-Key). */
    PENDING,
    ISSUED,
    /** Payments refused it (4xx); not retried. */
    FAILED
}
