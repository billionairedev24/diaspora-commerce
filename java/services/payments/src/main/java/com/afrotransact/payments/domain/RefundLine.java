package com.afrotransact.payments.domain;

import java.util.Map;

/**
 * The part of one line a refund gives back.
 *
 * @param commissionReversed commission returned to the seller (0 when the fee policy retains commission)
 */
public record RefundLine(String lineId, String sellerOrg, String taxJurisdiction, boolean shippingToPlatform,
                         Money items, Money shipping, Money tax, Money commissionReversed) {

    public Money total() {
        return items.plus(shipping).plus(tax);
    }

    /** What the seller gives back: items + their own shipping − commission returned to them. */
    public Money sellerDebit() {
        Money ownShipping = shippingToPlatform ? Money.zero(items.currency()) : shipping;
        return items.plus(ownShipping).minus(commissionReversed);
    }

    /**
     * Validates a line refund against what was captured and not yet refunded, and computes the commission to give
     * back cumulatively (round(commission × itemsRefundedAfter / items) − already reversed) so a line refunded in
     * several parts returns exactly its commission.
     */
    public static RefundLine allocate(PaymentLine pl, Money items, Money shipping, Money tax,
                                      boolean reverseCommission) {
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
        return new RefundLine(pl.lineId(), l.sellerOrg(), l.taxJurisdiction(), pl.shippingToPlatform(), items,
                shipping, tax, reversed);
    }
}
