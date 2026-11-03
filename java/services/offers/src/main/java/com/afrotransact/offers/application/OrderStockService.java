package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

/**
 * Keeps the declared quantity of seller-fulfilled offers true as orders come and go (inbox de-duplicated): units
 * sold leave the offer when the order is placed and come back when a placed shipment is cancelled. Fulfilled by
 * AfroTransact and pre-order lines are not touched (inventory-service owns those).
 */
public interface OrderStockService {

    /** orders.order.placed: takes the units of every seller-fulfilled line off the seller's offer of that variant. */
    void orderPlaced(UUID eventId, List<SoldLine> lines);

    /** orders.shipment.cancelled (placed order): puts the units of a seller-fulfilled shipment back. */
    void shipmentCancelled(UUID eventId, List<SoldLine> lines);

    /** Units of one variant sold by one seller. */
    record SoldLine(String sellerOrgId, String variantAtin, int quantity) {
    }
}
