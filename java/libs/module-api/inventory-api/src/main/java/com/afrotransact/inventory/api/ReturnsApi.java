package com.afrotransact.inventory.api;

/**
 * Restocking of returned units, which tax-returns asks over HTTP today ({@code POST /v1/returns}, scope
 * {@code inventory:adjust}), entered in-process under the calling module's principal. A caller without the scope is refused
 * with {@code ModuleApiException} status 403; failures carry the status the endpoint answers (404, 409, 422).
 */
public interface ReturnsApi {

    /**
     * RETURN_RESTOCK puts units back into their sellable lot; RETURN_DAMAGED holds them in the lot's damaged lot. Never more
     * than the order shipped. Idempotent per {@code idempotencyKey}.
     *
     * @param condition        the item condition as tax-returns names it (RETURN_RESTOCK or RETURN_DAMAGED outcomes)
     * @param receivingNodeId  the node the units go into, null for the lot's own
     */
    void acceptReturn(String orderId, String sku, int quantity, String condition, String reason, String receivingNodeId,
                      String idempotencyKey);
}
