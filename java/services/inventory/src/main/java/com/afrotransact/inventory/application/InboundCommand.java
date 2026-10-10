package com.afrotransact.inventory.application;

/**
 * Expected inbound units from an advance shipping notice (shown as "Coming soon" when nothing is on hand).
 *
 * @param qty positive to add expected units, negative to cancel them
 */
public record InboundCommand(String sku, String nodeId, String ownerOrg, int qty, String reference) {
}
