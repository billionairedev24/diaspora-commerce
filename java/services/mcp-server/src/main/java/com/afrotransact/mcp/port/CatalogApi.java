package com.afrotransact.mcp.port;

import java.util.Map;

/** catalog-service public reads, seller listings, moderation queue and category requests. */
public interface CatalogApi {
    Downstream publicProduct(String atin, String bearerToken);

    Downstream publicVariant(String atin, String bearerToken);

    Downstream products(String orgId, String status, String query, int page, int size, String bearerToken);

    /** By product id (UUID) or ATIN. */
    Downstream product(String idOrAtin, String bearerToken);

    Downstream requestCategory(Map<String, Object> request, String bearerToken);

    /** The review queue of category requests (staff, catalog:taxonomy); status null means pending review. */
    Downstream categoryRequests(String status, String bearerToken);
}
