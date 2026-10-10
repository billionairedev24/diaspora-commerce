package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A line-level (possibly partial) refund of captured lines. */
public record Refund(UUID id, UUID paymentId, String orderId, String stripeRefundId, Money amount,
                     RefundStatus status, String reason, List<RefundLine> lines, Instant createdAt,
                     Instant settledAt) {

    public Refund {
        lines = List.copyOf(lines);
    }

    public Refund settled(Instant at) {
        return new Refund(id, paymentId, orderId, stripeRefundId, amount, RefundStatus.SUCCEEDED, reason, lines,
                createdAt, at);
    }

    public Refund withStripeId(String stripeId) {
        return new Refund(id, paymentId, orderId, stripeId, amount, status, reason, lines, createdAt, settledAt);
    }
}
