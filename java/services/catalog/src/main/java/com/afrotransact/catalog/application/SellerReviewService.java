package com.afrotransact.catalog.application;

import java.util.UUID;

import com.afrotransact.catalog.domain.ProductReview;

/**
 * The product owner's side of reviews (S324): see the published reviews of their products and reply once to each.
 * Sellers can report a review (see {@link ProductReviewService#report}) but cannot edit, hide or remove one.
 */
public interface SellerReviewService {

    /**
     * Published reviews of the caller's organisation's products, newest first (staff name the organisation).
     *
     * @param unansweredOnly only reviews without a reply
     */
    PageView<ProductReview> list(String orgId, boolean unansweredOnly, int page, int size);

    /**
     * Replies once to a published review of the organisation's product ({@code catalog:write}, organisation
     * members).
     *
     * @throws com.afrotransact.catalog.domain.ReviewStateConflict {@code catalog.review-not-published},
     *                                                             {@code catalog.review-already-replied}
     */
    ProductReview reply(UUID reviewId, String text);
}
