package com.afrotransact.payments.domain;

import java.util.Map;

/**
 * The part of one line a refund gives back.
 *
 * @param commissionReversed  commission returned to the seller (0 when the fee policy retains commission)
 * @param deliveryFeeReversed AfroTransact Delivery pickup-delivery fee returned to the seller (customer-fault refunds
 *                            only, see {@link RefundFault}); 0 otherwise
 */
public record RefundLine(String lineId, String sellerOrg, String taxJurisdiction, boolean shippingToPlatform,
                         Money items, Money shipping, Money tax, Money commissionReversed, Money deliveryFeeReversed) {

    public RefundLine {
        deliveryFeeReversed = deliveryFeeReversed == null ? Money.zero(items.currency()) : deliveryFeeReversed;
    }

    /** A refund line that returns no delivery fee. */
    public RefundLine(String lineId, String sellerOrg, String taxJurisdiction, boolean shippingToPlatform,
                      Money items, Money shipping, Money tax, Money commissionReversed) {
        this(lineId, sellerOrg, taxJurisdiction, shippingToPlatform, items, shipping, tax, commissionReversed, null);
    }

    public Money total() {
        return items.plus(shipping).plus(tax);
    }

    /**
     * What the seller gives back: items + their own shipping − commission returned to them − delivery fee returned
     * to them.
     */
    public Money sellerDebit() {
        Money ownShipping = shippingToPlatform ? Money.zero(items.currency()) : shipping;
        return items.plus(ownShipping).minus(commissionReversed).minus(deliveryFeeReversed);
    }

    /** A seller-fault refund (the default). */
    public static RefundLine allocate(PaymentLine pl, Money items, Money shipping, Money tax,
                                      boolean reverseCommission) {
        return allocate(pl, items, shipping, tax, reverseCommission, RefundFault.SELLER);
    }

    /**
     * Validates a line refund against what was captured and not yet refunded, and computes the commission to give
     * back cumulatively (round(commission × itemsRefundedAfter / items) − already reversed) so a line refunded in
     * several parts returns exactly its commission. On AfroTransact Delivery lines the {@link RefundFault} decides the
     * delivery money: a customer-fault refund cannot give the delivery charge back
     * ({@code payments.delivery-charge-not-refundable}) and returns the seller's delivery fee the same cumulative way
     * as commission; a seller-fault refund keeps the fee.
     */
    public static RefundLine allocate(PaymentLine pl, Money items, Money shipping, Money tax,
                                      boolean reverseCommission, RefundFault fault) {
        if (!pl.isCaptured()) {
            throw new PaymentsRuleViolation("payments.line-not-captured", "Line " + pl.lineId()
                    + " has not been captured; cancel or skip its capture instead of refunding it",
                    Map.of("lines", "Line not captured"));
        }
        if (items.isNegative() || shipping.isNegative() || tax.isNegative()) {
            throw new PaymentsRuleViolation("payments.invalid-refund", "Refund amounts cannot be negative");
        }
        OrderLine l = pl.line();
        if (pl.refundedItems().plus(items).greaterThan(l.items())
                || pl.refundedShipping().plus(shipping).greaterThan(l.shipping())
                || pl.refundedTax().plus(tax).greaterThan(l.tax())) {
            throw new PaymentsRuleViolation("payments.refund-exceeds-line", "Refund exceeds what is left of line "
                    + pl.lineId(), Map.of("lines", "Exceeds captured amount not yet refunded"));
        }
        Money reversed = Money.zero(items.currency());
        if (reverseCommission && l.items().isPositive() && pl.commission().isPositive()) {
            long after = Math.multiplyExact(pl.commission().minor(), pl.refundedItems().plus(items).minor());
            long rounded = (after * 2 + l.items().minor()) / (2 * l.items().minor());
            reversed = Money.of(rounded, items.currency()).minus(pl.commissionReversed());
        }
        Money feeBack = Money.zero(items.currency());
        if (l.fulfilment() == Fulfilment.AFROTRANSACT_DELIVERY && fault == RefundFault.CUSTOMER) {
            if (shipping.isPositive()) {
                throw new PaymentsRuleViolation("payments.delivery-charge-not-refundable", "Line " + pl.lineId()
                        + ": the AfroTransact Delivery charge is not refunded when the customer is at fault",
                        Map.of("lines", "shippingMinor must be 0 for a customer-fault refund"));
            }
            feeBack = proRata(pl.deliveryFee(), pl.refundedItems().plus(items), l.items())
                    .minus(pl.deliveryFeeReversed());
        }
        return new RefundLine(pl.lineId(), l.sellerOrg(), l.taxJurisdiction(), pl.shippingToPlatform(), items,
                shipping, tax, reversed, feeBack);
    }

    /** round_half_up(amount × part / whole), 0 when nothing to share. */
    private static Money proRata(Money amount, Money part, Money whole) {
        if (!whole.isPositive() || !amount.isPositive()) {
            return Money.zero(amount.currency());
        }
        long scaled = Math.multiplyExact(amount.minor(), part.minor());
        return Money.of((scaled * 2 + whole.minor()) / (2 * whole.minor()), amount.currency());
    }
}
