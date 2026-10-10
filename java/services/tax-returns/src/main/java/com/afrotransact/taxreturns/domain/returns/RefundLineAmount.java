package com.afrotransact.taxreturns.domain.returns;

/**
 * Refund of one line. {@code itemsMinor} is what goes back to the customer for the items, after the restocking fee.
 *
 * @param lineId             order line id
 * @param quantity           units refunded (0 for a line that only carries the shipment's shipping, or an adjustment)
 * @param itemsMinor         items refund
 * @param shippingMinor      shipping refund
 * @param taxMinor           tax refund (reversed in the tax ledger)
 * @param restockingFeeMinor restocking fee kept
 */
public record RefundLineAmount(String lineId, int quantity, long itemsMinor, long shippingMinor, long taxMinor,
                               long restockingFeeMinor) {

    public long totalMinor() {
        return itemsMinor + shippingMinor + taxMinor;
    }
}
