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
     * @param group  caller's grouping key (orders: the order line id) for per-shipment commit / release; optional
     */
    public record Line(String sku, String nodeId, int qty, String group) {

        /** A line without group (callers that commit and release the whole order). */
        public Line(String sku, String nodeId, int qty) {
            this(sku, nodeId, qty, null);
        }
    }
}
