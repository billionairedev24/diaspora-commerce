package com.afrotransact.taxreturns.port;

import com.afrotransact.taxreturns.domain.returns.ItemCondition;

/** inventory-service {@code POST /v1/returns}: RETURN_RESTOCK or RETURN_DAMAGED. Throws {@link UpstreamException}. */
public interface InventoryGateway {

    /**
     * Posts an inspected return (SERVICE scope {@code inventory:returns}).
     *
     * @param receivingNodeId warehouse that received the parcel (the return centre); null = the node it shipped from
     */
    void acceptReturn(String orderId, String sku, int quantity, ItemCondition condition, String reason,
                      String receivingNodeId, String idempotencyKey);
}
