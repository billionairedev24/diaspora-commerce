package com.afrotransact.trustops.port;

import java.util.UUID;

/**
 * catalog-service moderation endpoints (contracts/openapi/catalog.yaml): {@code POST /v1/products/{id}/approve}
 * and {@code POST /v1/products/{id}/reject} with {@code {"note"}} (permission catalog:moderate).
 */
public interface CatalogModerationClient {

    void approve(UUID productId);

    void reject(UUID productId, String note);
}
