package com.afrotransact.payments.domain;

/**
 * Fees of one order line.
 *
 * @param shippingToPlatform the line's shipping is AfroTransact revenue (FBA / 1P), not the seller's
 */
public record LineSplit(String lineId, String sellerOrg, int commissionBps, Money commission, Money fbaFee,
                        boolean shippingToPlatform) {
}
