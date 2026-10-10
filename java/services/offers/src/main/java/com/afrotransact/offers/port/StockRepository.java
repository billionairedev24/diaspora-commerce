package com.afrotransact.offers.port;

import java.util.Collection;
import java.util.Map;

/**
 * Projection of inventory-service availability (inventory.availability.changed) per SKU and node, used for
 * Fulfilled-by-AfroTransact offers.
 */
public interface StockRepository {

    /** Stores the level unless a newer (or equal) version is already held. Returns true when stored. */
    boolean apply(StockLevel level);

    /** Available units summed over nodes for each (owner org, SKU); missing keys have no stock. */
    Map<StockKey, Integer> available(Collection<StockKey> keys);

    /** Available units of a SKU at one node, owned by an organisation. */
    record StockLevel(String sku, String nodeId, String ownerOrg, int available, long version) {
    }

    /** Owner organisation and inventory SKU. */
    record StockKey(String ownerOrg, String sku) {
    }
}
