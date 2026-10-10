package com.afrotransact.taxreturns.port;

import com.afrotransact.taxreturns.domain.returns.ItemCondition;

/** inventory-service {@code POST /v1/returns}: RETURN_RESTOCK or RETURN_DAMAGED. Throws {@link UpstreamException}. */
public interface InventoryGateway {

    void acceptReturn(String orderId, String sku, int quantity, ItemCondition condition, String reason,
                      String idempotencyKey);
}
