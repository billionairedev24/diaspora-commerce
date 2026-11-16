package com.afrotransact.mcp.port;

import java.util.Map;

/** catalog-service public reads, seller listings, moderation queue and category requests. */
public interface CatalogApi {
    Downstream publicProduct(String atin, CallerCredentials credentials);

    Downstream publicVariant(String atin, CallerCredentials credentials);

    Downstream products(String orgId, String status, String query, int page, int size, CallerCredentials credentials);

    /** By product id (UUID) or ATIN. */
    Downstream product(String idOrAtin, CallerCredentials credentials);

    Downstream requestCategory(Map<String, Object> request, CallerCredentials credentials);

    /** The review queue of category requests (staff, catalog:taxonomy); status null means pending review. */
    Downstream categoryRequests(String status, CallerCredentials credentials);
}
