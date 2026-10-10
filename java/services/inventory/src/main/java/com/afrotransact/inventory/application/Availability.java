package com.afrotransact.inventory.application;

import java.util.List;

/**
 * Sellable availability of one SKU: summed over nodes and per node. {@code available = on hand − reserved}.
 *
 * @param inbound units expected on inbound notices (storefront shows "Coming soon" when available is 0)
 */
public record Availability(String sku, int available, int inbound, boolean inStock, List<NodeAvailability> nodes) {

    /** Availability at one node. */
    public record NodeAvailability(String nodeId, int available, int inbound, long version) {
    }
}
