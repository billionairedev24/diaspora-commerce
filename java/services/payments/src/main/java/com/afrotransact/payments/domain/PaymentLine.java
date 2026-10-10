package com.afrotransact.payments.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An order line of a payment with its capture and refund state.
 *
 * @param captureId           capture that collected the line (null until captured)
 * @param commission          commission charged on the line at capture
 * @param fbaFee              FBA fee charged at capture
 * @param commissionReversed  commission given back by refunds so far
 * @param deliveryFee         seller pickup-delivery fee charged at capture (AfroTransact Delivery, 4250)
 * @param deliveryFeeReversed delivery fee given back to the seller by customer-fault refunds so far
 */
public record PaymentLine(OrderLine line, UUID captureId, Money commission, Money fbaFee, boolean shippingToPlatform,
                          Money refundedItems, Money refundedShipping, Money refundedTax, Money commissionReversed,
                          Money deliveryFee, Money deliveryFeeReversed) {

    public PaymentLine {
        Objects.requireNonNull(line, "line");
        Money zero = Money.zero(line.currency());
        deliveryFee = deliveryFee == null ? zero : deliveryFee;
        deliveryFeeReversed = deliveryFeeReversed == null ? zero : deliveryFeeReversed;
    }

    /** A line without delivery fees. */
    public PaymentLine(OrderLine line, UUID captureId, Money commission, Money fbaFee, boolean shippingToPlatform,
                       Money refundedItems, Money refundedShipping, Money refundedTax, Money commissionReversed) {
        this(line, captureId, commission, fbaFee, shippingToPlatform, refundedItems, refundedShipping, refundedTax,
                commissionReversed, null, null);
    }

    public static PaymentLine uncaptured(OrderLine line) {
        Money zero = Money.zero(line.currency());
        return new PaymentLine(line, null, zero, zero, line.fulfilment().platformShips(), zero, zero, zero, zero);
    }

    public String lineId() {
        return line.lineId();
    }

    public boolean isCaptured() {
        return captureId != null;
    }

    public PaymentLine captured(UUID capture, LineSplit split) {
        return new PaymentLine(line, capture, split.commission(), split.fbaFee(), split.shippingToPlatform(),
                refundedItems, refundedShipping, refundedTax, commissionReversed, split.deliveryFee(),
                deliveryFeeReversed);
    }

    public PaymentLine refunded(RefundLine r) {
        return new PaymentLine(line, captureId, commission, fbaFee, shippingToPlatform,
                refundedItems.plus(r.items()), refundedShipping.plus(r.shipping()), refundedTax.plus(r.tax()),
                commissionReversed.plus(r.commissionReversed()), deliveryFee,
                deliveryFeeReversed.plus(r.deliveryFeeReversed()));
    }

    public Money refundedTotal() {
        return refundedItems.plus(refundedShipping).plus(refundedTax);
    }

    /** What can still be refunded: items + shipping + tax not yet refunded (0 until captured). */
    public Money refundable() {
        return isCaptured() ? line.total().minus(refundedTotal()) : Money.zero(line.currency());
    }
}
