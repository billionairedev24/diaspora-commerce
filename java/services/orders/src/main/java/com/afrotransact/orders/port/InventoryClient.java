package com.afrotransact.orders.port;

import java.util.List;

/**
 * inventory-service reservations (SERVICE principal with inventory:reserve). The order id is the idempotency key of
 * every call, so saga retries never reserve twice.
 */
public interface InventoryClient {

    /** All lines or none; throws a non-retryable {@link UpstreamFailure} {@code inventory.insufficient-stock}. */
    List<Reserved> reserve(String orderId, List<Item> items);

    /** Payment authorised: the hold no longer expires. */
    void confirm(String orderId);

    /** Ship: on hand goes down (first AfroTransact-fulfilled pickup of the order). */
    void commit(String orderId);

    /** Give the stock back (payment failure, timeout, cancellation). Idempotent. */
    void release(String orderId, String reason);

    /**
     * Ship only the reservation lines of these groups (the order line ids of one shipment). Idempotent. Throws a
     * non-retryable {@link UpstreamFailure} with status 404 when the reservation has no such groups (orders reserved
     * before groups existed): callers then fall back to {@link #commit(String)}.
     */
    void commitGroups(String orderId, String shipmentId, List<String> groups);

    /**
     * Give back only the reservation lines of these groups (one cancelled shipment). Idempotent; 404 as for
     * {@link #commitGroups}.
     */
    void releaseGroups(String orderId, String shipmentId, List<String> groups, String reason);

    /**
     * One order line to reserve (variant ATIN).
     *
     * @param group the order line id, so the line can be shipped or released with its shipment
     */
    record Item(String sku, int qty, String group) {

        public Item(String sku, int qty) {
            this(sku, qty, null);
        }
    }

    /** One reserved line with the node inventory allocated it from (group = the order line id when sent). */
    record Reserved(String sku, String nodeId, int qty, String group) {

        public Reserved(String sku, String nodeId, int qty) {
            this(sku, nodeId, qty, null);
        }
    }
}
