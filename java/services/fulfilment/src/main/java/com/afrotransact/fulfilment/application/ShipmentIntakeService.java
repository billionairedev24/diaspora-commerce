package com.afrotransact.fulfilment.application;

import java.util.UUID;

/**
 * Consumes orders-service events (inbox-deduplicated): {@code orders.order.placed} creates pick/pack tasks for
 * FBA / Retail 1P shipments and puts AfroTransact Delivery shipments in the dispatch pool;
 * {@code orders.shipment.cancelled} cancels them.
 */
public interface ShipmentIntakeService {

    /** @return number of tasks + delivery shipments created (0 on redelivery) */
    int orderPlaced(UUID eventId, PlacedOrder order);

    /** @return true when something was cancelled */
    boolean shipmentCancelled(UUID eventId, String shipmentId, String reason);
}
