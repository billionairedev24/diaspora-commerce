package com.afrotransact.retail.port;

import java.util.UUID;

/** A lot inventory created for a receipt. */
public record InventoryLot(UUID id, String sku, String lotNo, String nodeId, int qtyOnHand, String status,
                           long unitLandedCostMinor) {
}
