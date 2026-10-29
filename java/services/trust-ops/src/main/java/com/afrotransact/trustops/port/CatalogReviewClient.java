package com.afrotransact.trustops.port;

import java.util.UUID;

import com.afrotransact.trustops.domain.ModerationReasonCode;

/**
 * catalog-service product-review moderation (contracts/openapi/catalog.yaml), with trust-ops' service token (scope
 * {@code catalog:moderate}): {@code POST /v1/product-reviews/{id}/approve} with {@code {"note"}} publishes a review,
 * {@code POST /v1/product-reviews/{id}/reject} with {@code {"reasonCode", "note"}} refuses one that waits for
 * moderation and {@code POST /v1/product-reviews/{id}/remove} takes a published one down. All three are idempotent in
 * catalog.
 */
public interface CatalogReviewClient {

    void approve(UUID reviewId, String note);

    void reject(UUID reviewId, ModerationReasonCode reasonCode, String note);

    void remove(UUID reviewId, ModerationReasonCode reasonCode, String note);
}
