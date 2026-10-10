package com.afrotransact.taxreturns.domain.returns;

import java.time.Instant;

/**
 * An order line as seen by returns: what was bought, when its shipment was delivered and how much is already returned.
 *
 * @param lineId           order line id
 * @param shipmentId       shipment (sub-order) id
 * @param atin             variant ATIN (inventory SKU)
 * @param title            title snapshot
 * @param quantity         units bought
 * @param unitPriceMinor   unit price
 * @param taxMinor         tax charged on the line
 * @param productType      catalog product type (null when unknown)
 * @param perishable       food or other perishables: report a problem instead of a return
 * @param deliveredAt      delivery time (null until delivered)
 * @param returnedQuantity units in open or completed RMAs
 */
public record DeliveredLine(String lineId, String shipmentId, String atin, String title, int quantity,
                            long unitPriceMinor, long taxMinor, String productType, boolean perishable,
                            Instant deliveredAt, int returnedQuantity) {

    public int remainingQuantity() {
        return Math.max(0, quantity - returnedQuantity);
    }
}
