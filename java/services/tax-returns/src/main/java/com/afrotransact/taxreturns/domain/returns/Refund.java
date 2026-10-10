package com.afrotransact.taxreturns.domain.returns;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A refund orchestrated by this service and sent to payments-service with {@code Idempotency-Key = id}.
 *
 * @param id               refund id (also the payments Idempotency-Key)
 * @param kind             return or adjustment
 * @param rmaId            RMA (null for adjustments)
 * @param orderId          order
 * @param paymentId        payments-service payment
 * @param currency         ISO currency
 * @param status           progress
 * @param lines            per-line amounts
 * @param reason           reason sent to payments
 * @param requestKey       caller idempotency key (unique)
 * @param paymentsRefundId payments-service refund id once issued
 * @param failure          last failure message
 * @param attempts         payments calls made
 * @param createdAt        created
 * @param issuedAt         issued
 * @param version          optimistic-lock version
 */
public record Refund(UUID id, RefundKind kind, UUID rmaId, UUID orderId, UUID paymentId, String currency,
                     RefundStatus status, List<RefundLineAmount> lines, String reason, String requestKey,
                     String paymentsRefundId, String failure, int attempts, Instant createdAt, Instant issuedAt,
                     long version) {

    public Refund {
        lines = List.copyOf(lines);
    }

    public RefundBreakdown breakdown() {
        return new RefundBreakdown(lines);
    }

    public Refund issued(String refundIdAtPayments, Instant at) {
        return new Refund(id, kind, rmaId, orderId, paymentId, currency, RefundStatus.ISSUED, lines, reason,
                requestKey, refundIdAtPayments, null, attempts + 1, createdAt, at, version);
    }

    public Refund failed(String message) {
        return new Refund(id, kind, rmaId, orderId, paymentId, currency, RefundStatus.FAILED, lines, reason,
                requestKey, paymentsRefundId, message, attempts + 1, createdAt, issuedAt, version);
    }

    public Refund retryLater(String message) {
        return new Refund(id, kind, rmaId, orderId, paymentId, currency, RefundStatus.PENDING, lines, reason,
                requestKey, paymentsRefundId, message, attempts + 1, createdAt, issuedAt, version);
    }

    /** Back to pending for a manual retry of a failed refund. */
    public Refund reopened() {
        return new Refund(id, kind, rmaId, orderId, paymentId, currency, RefundStatus.PENDING, lines, reason,
                requestKey, paymentsRefundId, failure, attempts, createdAt, issuedAt, version);
    }
}
