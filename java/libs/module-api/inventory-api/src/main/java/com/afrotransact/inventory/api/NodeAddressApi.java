package com.afrotransact.inventory.api;

import java.util.Optional;

/**
 * The street address of a fulfilment node, as {@code GET /v1/nodes/{id}/address} answers it (permission
 * {@code inventory:read}); entered under the calling module's in-process principal.
 */
public interface NodeAddressApi {

    /** @return the node's address, empty when the node is unknown or has none (HTTP 404) */
    Optional<NodeAddress> addressOf(String nodeId);

    record NodeAddress(String line1, String line2, String city, String region, String postalCode, String country) {
    }
}
