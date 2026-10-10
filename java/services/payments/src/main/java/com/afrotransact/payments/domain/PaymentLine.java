package com.afrotransact.payments.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An order line of a payment with its capture and refund state.
 *
 * @param captureId         capture that collected the line (null until captured)
 * @param commission        commission charged on the line at capture
 * @param fbaFee            FBA fee charged at capture
 * @param commissionReversed commission given back by refunds so far
 */
public record PaymentLine(OrderLine line, UUID captureId, Money commission, Money fbaFee, boolean shippingToPlatform,
                          Money refundedItems, Money refundedShipping, Money refundedTax, Money commissionReversed) {

    public PaymentLine {
        Objects.requireNonNull(line, "line");
    }

    public static PaymentLine uncaptured(OrderLine line) {
        Money zero = Money.zero(line.currency());
        return new PaymentLine(line, null, zero, zero, line.fulfilment() == Fulfilment.FBA, zero, zero, zero, zero);
    }

    public String lineId() {
        return line.lineId();
    }

    public boolean isCaptured() {
        return captureId != null;
    }

    public PaymentLine captured(UUID capture, LineSplit split) {
        return new PaymentLine(line, capture, split.commission(), split.fbaFee(), split.shippingToPlatform(),
                refundedItems, refundedShipping, refundedTax, commissionReversed);
    }

    public PaymentLine refunded(RefundLine r) {
        return new PaymentLine(line, captureId, commission, fbaFee, shippingToPlatform,
                refundedItems.plus(r.items()), refundedShipping.plus(r.shipping()), refundedTax.plus(r.tax()),
                commissionReversed.plus(r.commissionReversed()));
    }

    public Money refundedTotal() {
        return refundedItems.plus(refundedShipping).plus(refundedTax);
    }
}
