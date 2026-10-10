package com.afrotransact.trustops.port;

import java.util.UUID;

import com.afrotransact.trustops.domain.ModerationReasonCode;

/**
 * catalog-service moderation endpoints (contracts/openapi/catalog.yaml), permission catalog:moderate:
 * {@code POST /v1/products/{id}/approve}, {@code POST /v1/products/{id}/reject} with {@code {"note"}} for new
 * submissions, and {@code POST /v1/products/{id}/takedown} with {@code {"reasonCode", "note"}} for live listings.
 */
public interface CatalogModerationClient {

    void approve(UUID productId);

    void reject(UUID productId, String note);

    /** Takes a live (or paused) listing down; idempotent in catalog. */
    void takeDown(UUID productId, ModerationReasonCode reasonCode, String note);
}
