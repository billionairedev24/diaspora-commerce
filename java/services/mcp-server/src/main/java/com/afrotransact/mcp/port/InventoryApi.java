package com.afrotransact.mcp.port;

import java.util.List;

/** inventory-service availability and positions. */
public interface InventoryApi {
    Downstream availability(List<String> skus, String bearerToken);

    Downstream positions(String ownerOrg, String sku, boolean lowStock, String bearerToken);
}
