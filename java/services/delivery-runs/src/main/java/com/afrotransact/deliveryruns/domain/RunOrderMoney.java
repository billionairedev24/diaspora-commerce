package com.afrotransact.deliveryruns.domain;

import java.time.Instant;

/**
 * What one order of a run paid and what was handed back (S265). Null amounts were never reported: the run's P&amp;L
 * falls back to the run's fee for them and says so.
 *
 * @param feeMinor          the delivery fee the order was charged
 * @param totalMinor        everything the customer paid, delivery fee included
 * @param kind              how the order is delivered
 * @param commissionMinor   what the platform keeps on the goods (seller commission and fees), as payments reported it
 * @param refundedMinor     money handed back to the customer so far
 * @param refundedFeeMinor  the part of it that was delivery fee
 * @param refundedAt        when the last refund was issued
 */
public record RunOrderMoney(Long feeMinor, Long totalMinor, DeliveryKind kind, Long commissionMinor,
                            long refundedMinor, long refundedFeeMinor, Instant refundedAt) {

    /** Nothing reported yet. */
    public static final RunOrderMoney NONE = new RunOrderMoney(null, null, null, null, 0, 0, null);

    public RunOrderMoney {
        if ((feeMinor != null && feeMinor < 0) || (totalMinor != null && totalMinor < 0)
                || (commissionMinor != null && commissionMinor < 0) || refundedMinor < 0 || refundedFeeMinor < 0) {
            throw new RuleViolation("delivery-runs.invalid-amount", "Amounts cannot be negative");
        }
    }

    /** What checkout charged: the fee, the order total (null when not sent) and how it is delivered. */
    public static RunOrderMoney charged(Long feeMinor, Long totalMinor, DeliveryKind kind) {
        return new RunOrderMoney(feeMinor, totalMinor, kind, null, 0, 0, null);
    }

    public boolean feeKnown() {
        return feeMinor != null;
    }

    public boolean refunded() {
        return refundedMinor > 0 || refundedFeeMinor > 0;
    }

    /** Commission payments reported on a capture; captures of one order add up. */
    public RunOrderMoney withCommission(long addedMinor) {
        return new RunOrderMoney(feeMinor, totalMinor, kind, (commissionMinor == null ? 0 : commissionMinor)
                + addedMinor, refundedMinor, refundedFeeMinor, refundedAt);
    }

    /** A refund payments issued; refunds of one order add up. */
    public RunOrderMoney withRefund(long amountMinor, long refundedFee, Instant at) {
        return new RunOrderMoney(feeMinor, totalMinor, kind, commissionMinor, refundedMinor + amountMinor,
                refundedFeeMinor + refundedFee, at);
    }
}
