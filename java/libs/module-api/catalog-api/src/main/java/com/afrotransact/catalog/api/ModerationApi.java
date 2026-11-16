package com.afrotransact.catalog.api;

import java.util.UUID;

/**
 * The moderation decisions trust-ops makes over HTTP today ({@code POST /v1/products/{id}/approve|reject|takedown} and
 * {@code POST /v1/product-reviews/{id}/approve|reject|remove}, scope {@code catalog:moderate}), entered in-process under the calling
 * module's principal. A caller without the scope is refused with {@code ModuleApiException} 403; the other refusals keep the status and
 * code the endpoint answers (404, 409 {@code catalog.invalid-transition} / {@code catalog.review-*}, 422 {@code catalog.tax-not-ready},
 * 503 when the tax or offers check cannot answer). Reason codes are the enum names the JSON bodies carry.
 */
public interface ModerationApi {

    /** Approves a product in review (through the selling approval and the tax gate). */
    void approveProduct(UUID productId);

    /** Rejects a product in review; the note is required (status 400 otherwise, as the request validation). */
    void rejectProduct(UUID productId, String note);

    /** Takes a live or paused listing down; idempotent. */
    void takeDownProduct(UUID productId, String listingReasonCode, String note);

    /** Publishes a pending review or reinstates a rejected or removed one. */
    void approveReview(UUID reviewId, String note);

    /** Refuses a review that waits for moderation. */
    void rejectReview(UUID reviewId, String reviewReasonCode, String note);

    /** Takes a review down, published or not. */
    void removeReview(UUID reviewId, String reviewReasonCode, String note);
}
