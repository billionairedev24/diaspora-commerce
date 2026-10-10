package com.afrotransact.payments.domain;

/**
 * Fees of one order line.
 *
 * @param shippingToPlatform the line's shipping is booked to 4200 AfroTransact shipping (a marketplace seller's FBA
 *                           line); false for seller-shipped lines and for every AfroTransact Retail line, whose
 *                           shipping stays in Retail's row and is booked with it to 4400 Retail sales
 */
public record LineSplit(String lineId, String sellerOrg, int commissionBps, Money commission, Money fbaFee,
                        boolean shippingToPlatform) {
}
