package com.afrotransact.taxreturns.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.PlacedOrder;

/** Snapshots of placed orders (from orders-service events). */
public interface OrderSnapshotRepository {

    /** Stores the order unless it is already known; true when inserted. */
    boolean insert(PlacedOrder order);

    /** Records the delivery time of a shipment; false when the shipment is unknown. */
    boolean markDelivered(UUID shipmentId, Instant deliveredAt);

    Optional<PlacedOrder> find(UUID orderId);

    Optional<PlacedOrder> findByNumber(String orderNumber);
}
