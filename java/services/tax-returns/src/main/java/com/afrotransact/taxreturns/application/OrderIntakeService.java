package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;

/** Keeps the order snapshot returns need and books the tax collected on placed orders. */
public interface OrderIntakeService {

    /** Consumer of orders.order.placed; false for a re-delivery. */
    boolean onOrderPlaced(UUID eventId, OrderPlaced event);

    /** Consumer of orders.shipment.delivered; false for a re-delivery or an unknown shipment. */
    boolean onShipmentDelivered(UUID eventId, UUID shipmentId, Instant deliveredAt);

    /**
     * The order snapshot; when this service never saw the order's event (placed before it started, or a lagging
     * consumer) it is backfilled from orders-service's lookup — snapshot and tax sale, exactly as the event would.
     */
    Optional<PlacedOrder> order(UUID orderId);

    /**
     * The customer's address for return labels and pickups: the snapshot's street, else the orders lookup's ship-to
     * (kept on the snapshot). Never typed by the customer.
     */
    Optional<ReturnAddress> customerAddress(PlacedOrder order);
}
