package com.afrotransact.inventory.domain;

/**
 * Why a reservation line cannot be satisfied.
 *
 * @param nodeId    the node asked for, or the best node when the caller let inventory choose (null when none stock it)
 * @param requested units asked for (summed over lines of the same SKU and node)
 * @param available units that could be reserved right now
 */
public record Shortfall(String sku, String nodeId, int requested, int available) {

    public int missing() {
        return requested - available;
    }
}
