package com.afrotransact.taxreturns.domain.returns;

/** What refunds (pending or issued) already took from an order line. */
public record RefundedTotals(int quantity, long itemsMinor, long shippingMinor, long taxMinor) {

    public static final RefundedTotals NONE = new RefundedTotals(0, 0L, 0L, 0L);
}
