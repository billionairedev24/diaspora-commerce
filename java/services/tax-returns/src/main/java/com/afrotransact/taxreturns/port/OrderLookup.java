package com.afrotransact.taxreturns.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.PlacedOrder;

/**
 * orders-service {@code GET /v1/lookup/orders/{id}} (client scope {@code orders:lookup}): the placed order with its
 * customer, full ship-to address, lines and delivery times. Used to backfill orders placed before this service
 * consumed their event and to find the street address of older snapshots. Throws {@link UpstreamException}.
 */
public interface OrderLookup {

    /** Empty when orders-service does not know the order (404). */
    Optional<PlacedOrder> placedOrder(UUID orderId);
}
