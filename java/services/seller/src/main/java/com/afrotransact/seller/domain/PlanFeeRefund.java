package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A refund of a plan fee: a credit on the invoice (it never reopens it). Staff refunds go through Stripe with an
 * idempotency key; a refund made in the Stripe dashboard is recorded when {@code charge.refunded} arrives.
 *
 * @param status  PENDING (asked at Stripe), SUCCEEDED (Stripe's amount_refunded covers it) or FAILED
 * @param origin  STAFF or DASHBOARD
 */
public record PlanFeeRefund(UUID id, String invoiceId, String orgId, String paymentIntentId, String stripeRefundId,
                            long amountCents, String status, String origin, String reasonCode, String note,
                            String requestedBy, String idempotencyKey, Instant createdAt, Instant updatedAt) {

    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String STAFF = "STAFF";
    public static final String DASHBOARD = "DASHBOARD";

    public boolean counts() {
        return !FAILED.equals(status);
    }
}
