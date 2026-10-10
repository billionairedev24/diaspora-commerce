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

    /** One SKU to reserve (variant ATIN). */
    record Item(String sku, int qty) {
    }

    /** One reserved line with the node inventory allocated it from. */
    record Reserved(String sku, String nodeId, int qty) {
    }
}
