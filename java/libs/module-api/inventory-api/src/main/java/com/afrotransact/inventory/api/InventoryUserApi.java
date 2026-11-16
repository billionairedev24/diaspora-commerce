package com.afrotransact.inventory.api;

import java.util.List;

import com.afrotransact.module.EndUserActor;

/**
 * Stock reads for the signed-in person (MCP tools): the in-process equivalent of relaying their bearer token to inventory-service.
 * Inventory's own checks decide as for the HTTP request (a seller sees only its own FBA positions; positions need
 * {@code inventory:read}). The calling module needs the delegation scope {@value #READ_SCOPE}. Results are the JSON tree of the HTTP
 * response; failures are {@code ModuleApiException} with the HTTP problem's status and code.
 */
public interface InventoryUserApi {

    /** Delegation scope for this API. */
    String READ_SCOPE = "inventory:user.read";

    /** {@code GET /v1/availability?skus=}: public sellable availability per SKU (at most 100). */
    Object availability(EndUserActor user, List<String> skus);

    /** {@code GET /v1/positions}: stock positions the person may see; {@code ownerOrg} and {@code sku} may be null. */
    Object positions(EndUserActor user, String ownerOrg, String sku, boolean lowStock);
}
