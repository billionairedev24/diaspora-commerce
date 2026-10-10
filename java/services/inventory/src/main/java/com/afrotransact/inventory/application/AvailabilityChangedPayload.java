package com.afrotransact.inventory.application;

/** Payload of {@code inventory.availability.changed}; search, storefront, cart and Lola consume it. */
public record AvailabilityChangedPayload(String sku, String nodeId, String ownerOrg, int available, int onHand,
                                         int reserved, int inbound, int damaged, long version) {
}
