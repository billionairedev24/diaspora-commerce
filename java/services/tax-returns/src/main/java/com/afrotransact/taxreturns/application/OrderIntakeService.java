package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.UUID;

/** Keeps the order snapshot returns need and books the tax collected on placed orders. */
public interface OrderIntakeService {

    /** Consumer of orders.order.placed; false for a re-delivery. */
    boolean onOrderPlaced(UUID eventId, OrderPlaced event);

    /** Consumer of orders.shipment.delivered; false for a re-delivery or an unknown shipment. */
    boolean onShipmentDelivered(UUID eventId, UUID shipmentId, Instant deliveredAt);
}
