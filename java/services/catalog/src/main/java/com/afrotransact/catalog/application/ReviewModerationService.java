package com.afrotransact.catalog.application;

import java.util.UUID;

import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.ReviewReasonCode;
import com.afrotransact.catalog.domain.ReviewStatus;

/**
 * Staff and trust-ops decisions on product reviews (S324, {@code catalog:moderate}). Decisions are idempotent, so a
 * retried queue decision is safe; the product's rating follows every decision.
 */
public interface ReviewModerationService {

    /** Reviews in a state (null = every state but deleted), newest first, optionally of one product. */
    PageView<ProductReview> list(ReviewStatus status, String atin, int page, int size);

    ProductReview get(UUID reviewId);

    /** Publishes a pending review, or reinstates a rejected or removed one. */
    ProductReview approve(UUID reviewId, String note);

    /** Refuses a review that is waiting for moderation, with a reason the customer sees. */
    ProductReview reject(UUID reviewId, ReviewReasonCode reasonCode, String note);

    /** Takes a review down, published or not, with a reason the customer sees. */
    ProductReview remove(UUID reviewId, ReviewReasonCode reasonCode, String note);
}
