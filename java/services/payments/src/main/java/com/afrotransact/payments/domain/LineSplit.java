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
 * @param commissionBps      the line's take rate: its commission (PRODUCT) or its connection fee (SERVICE)
 * @param commission         what that rate takes from the line: commission (4000) for products, connection fee (4050)
 *                           for services
 * @param kind               PRODUCT or SERVICE
 * @param rateSource         where the rate comes from (OVERRIDE, PLAN, CATEGORY, DEFAULT, RETAIL); null for lines
 *                           created before rates were snapshotted
 */
public record LineSplit(String lineId, String sellerOrg, int commissionBps, Money commission, Money fbaFee,
                        boolean shippingToPlatform, Money deliveryFee, Money preorderDeliveryFee, FeeKind kind,
                        String rateSource) {

    public LineSplit {
        deliveryFee = deliveryFee == null ? Money.zero(commission.currency()) : deliveryFee;
        preorderDeliveryFee = preorderDeliveryFee == null ? Money.zero(commission.currency()) : preorderDeliveryFee;
        kind = kind == null ? FeeKind.PRODUCT : kind;
    }

    /** A product line. */
    public LineSplit(String lineId, String sellerOrg, int commissionBps, Money commission, Money fbaFee,
                     boolean shippingToPlatform, Money deliveryFee, Money preorderDeliveryFee) {
        this(lineId, sellerOrg, commissionBps, commission, fbaFee, shippingToPlatform, deliveryFee,
                preorderDeliveryFee, FeeKind.PRODUCT, null);
    }

    /** The connection fee of a service line (0 for products). */
    public Money connectionFee() {
        return kind == FeeKind.SERVICE ? commission : Money.zero(commission.currency());
    }

    /** The commission of a product line (0 for services). */
    public Money productCommission() {
        return kind == FeeKind.SERVICE ? Money.zero(commission.currency()) : commission;
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
