package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A line-level (possibly partial) refund of captured lines. Each part goes back to what paid it: the card portion to
 * the card (a Stripe refund), the credit portion as credit. {@code cardAsCredit} is the one-way choice of the shopper
 * or staff to take the card portion as credit as well; it is never converted back to cash.
 *
 * @param credit       the part of the refund that was paid with credit (it goes back as credit)
 * @param cardAsCredit the part that was paid with the card was returned as credit by choice
 */
public record Refund(UUID id, UUID paymentId, String orderId, String stripeRefundId, Money amount,
                     RefundStatus status, String reason, List<RefundLine> lines, Instant createdAt,
                     Instant settledAt, Money credit, boolean cardAsCredit) {

    public Refund {
        lines = List.copyOf(lines);
        credit = credit == null ? Money.zero(amount.currency()) : credit;
    }

    /** A refund that goes entirely back to the card. */
    public Refund(UUID id, UUID paymentId, String orderId, String stripeRefundId, Money amount,
                  RefundStatus status, String reason, List<RefundLine> lines, Instant createdAt, Instant settledAt) {
        this(id, paymentId, orderId, stripeRefundId, amount, status, reason, lines, createdAt, settledAt, null, false);
    }

    /** The part of the refund that was paid with the card. */
    public Money cardAmount() {
        return amount.minus(credit);
    }

    /** What goes back to the card through Stripe: the card part, unless it was taken as credit. */
    public Money stripeAmount() {
        return cardAsCredit ? Money.zero(amount.currency()) : cardAmount();
    }

    /** What goes back as credit: the credit part, and the card part too when it was taken as credit. */
    public Money creditReturned() {
        return cardAsCredit ? amount : credit;
    }

    public Refund settled(Instant at) {
        return new Refund(id, paymentId, orderId, stripeRefundId, amount, RefundStatus.SUCCEEDED, reason, lines,
                createdAt, at, credit, cardAsCredit);
    }

    public Refund withStripeId(String stripeId) {
        return new Refund(id, paymentId, orderId, stripeId, amount, status, reason, lines, createdAt, settledAt,
                credit, cardAsCredit);
    }

    public Refund withCredit(Money creditPart, boolean asCredit) {
        return new Refund(id, paymentId, orderId, stripeRefundId, amount, status, reason, lines, createdAt,
                settledAt, creditPart, asCredit);
    }
}
