package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Keeps the pre-order orders projection from orders-service events (consumer {@code delivery-runs-preorders},
 * de-duplicated on event id through the platform inbox). Each call is one transaction.
 */
public interface PreorderProjectionService {

    String CONSUMER = "delivery-runs-preorders";

    /** {@code orders.order.placed} with PREORDER packages: projects the order (or completes a late add). */
    void onOrderPlaced(UUID eventId, OrderPlaced event);

    /** {@code orders.shipment.cancelled}: the package is cancelled; the order when none is left. */
    void onShipmentCancelled(UUID eventId, String orderId, String shipmentId, Instant at);

    /** {@code orders.order.cancelled} of a placed order: the pre-order is cancelled. */
    void onOrderCancelled(UUID eventId, String orderId, Instant at);
}
