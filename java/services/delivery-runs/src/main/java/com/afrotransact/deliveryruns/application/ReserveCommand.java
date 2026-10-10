package com.afrotransact.deliveryruns.application;

import java.util.UUID;

/**
 * orders-service reserves a slot for an order.
 *
 * @param runId        the run the customer chose at checkout
 * @param orderId      order id; reserving the same order again returns its existing slot (idempotent)
 * @param customerId   customer (token subject)
 * @param packages     packages the order adds to the run (default 1)
 * @param allowNextRun when the run is full or past cutoff, take a slot on the location's next open run instead
 *                     (default true); false refuses with 409
 */
public record ReserveCommand(UUID runId, String orderId, String customerId, Integer packages, Boolean allowNextRun) {
}
