package com.afrotransact.inventory.application;

import java.util.List;

/**
 * Reserve every line of an order, or nothing.
 *
 * @param orderId        idempotency key of the reservation: a retry with the same order id never reserves twice
 * @param idempotencyKey the request's Idempotency-Key header (stored for tracing)
 */
public record ReserveCommand(String orderId, String idempotencyKey, List<Line> lines) {

    public ReserveCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * One order line.
     *
     * @param nodeId fulfilment node chosen by the promise service; null lets inventory pick the node with stock
     */
    public record Line(String sku, String nodeId, int qty) {
    }
}
