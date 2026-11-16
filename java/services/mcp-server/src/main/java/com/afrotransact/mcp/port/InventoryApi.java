package com.afrotransact.mcp.port;

import java.util.List;

/** inventory-service availability and positions. */
public interface InventoryApi {
    Downstream availability(List<String> skus, CallerCredentials credentials);

    Downstream positions(String ownerOrg, String sku, boolean lowStock, CallerCredentials credentials);
}
