package com.afrotransact.payments.domain;

/**
 * Fees of one order line.
 *
 * @param shippingToPlatform the line's shipping is booked to 4200 AfroTransact shipping (a marketplace seller's FBA
 *                           or AfroTransact Delivery line); false for seller-shipped lines and for every AfroTransact
 *                           Retail line, whose shipping stays in Retail's row and is booked with it to 4400 Retail
 *                           sales
 * @param deliveryFee        seller pickup-delivery fee charged on the line (AfroTransact Delivery, 4250); 0 otherwise
 * @param preorderDeliveryFee the line's shipping when it is a pre-order delivery fee (S272): AfroTransact's, booked to
 *                           4250 Local delivery fees instead of 4200; 0 otherwise
 */
public record LineSplit(String lineId, String sellerOrg, int commissionBps, Money commission, Money fbaFee,
                        boolean shippingToPlatform, Money deliveryFee, Money preorderDeliveryFee) {

    public LineSplit {
        deliveryFee = deliveryFee == null ? Money.zero(commission.currency()) : deliveryFee;
        preorderDeliveryFee = preorderDeliveryFee == null ? Money.zero(commission.currency()) : preorderDeliveryFee;
    }

    /** A line that is not a pre-order. */
    public LineSplit(String lineId, String sellerOrg, int commissionBps, Money commission, Money fbaFee,
                     boolean shippingToPlatform, Money deliveryFee) {
        this(lineId, sellerOrg, commissionBps, commission, fbaFee, shippingToPlatform, deliveryFee, null);
    }

    /** A line without a delivery fee. */
    public LineSplit(String lineId, String sellerOrg, int commissionBps, Money commission, Money fbaFee,
                     boolean shippingToPlatform) {
        this(lineId, sellerOrg, commissionBps, commission, fbaFee, shippingToPlatform, null, null);
    }
}
