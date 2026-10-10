package com.afrotransact.taxreturns.domain.returns;

/**
 * An order line as charged (payments-service line) and what has already been refunded from it.
 * All amounts are minor units of the order currency.
 *
 * @param lineId                order line id
 * @param shipmentId            shipment the line belongs to (shipping is refunded per shipment)
 * @param quantity              units charged
 * @param itemsMinor            items amount charged (after discount)
 * @param shippingMinor         shipping charged on this line (orders puts a shipment's shipping on its first line)
 * @param taxMinor              tax charged on this line
 * @param refundedQuantity      units already refunded
 * @param refundedItemsMinor    items amount already consumed by refunds (refunded + restocking fees kept)
 * @param refundedShippingMinor shipping already refunded
 * @param refundedTaxMinor      tax already refunded
 */
public record RefundableLine(String lineId, String shipmentId, int quantity, long itemsMinor, long shippingMinor,
                             long taxMinor, int refundedQuantity, long refundedItemsMinor, long refundedShippingMinor,
                             long refundedTaxMinor) {

    public int remainingQuantity() {
        return Math.max(0, quantity - refundedQuantity);
    }

    public long remainingItemsMinor() {
        return Math.max(0L, itemsMinor - refundedItemsMinor);
    }

    public long remainingShippingMinor() {
        return Math.max(0L, shippingMinor - refundedShippingMinor);
    }

    public long remainingTaxMinor() {
        return Math.max(0L, taxMinor - refundedTaxMinor);
    }
}
