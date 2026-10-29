package com.afrotransact.retail.port;

import java.util.UUID;

/**
 * A lot inventory created for a receipt.
 *
 * @param unitLandedCostMinor the lot's unit landed cost in US cents (retail books in USD)
 */
public record InventoryLot(UUID id, String sku, String lotNo, String nodeId, int qtyOnHand, String status,
                           long unitLandedCostMinor) {
}
