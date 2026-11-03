package com.afrotransact.trustops.domain;

/** What a moderation queue item is about. */
public enum ModerationItemType {
    /** A new submission waiting for review (catalog.product.created, IN_REVIEW): allow approves, remove rejects. */
    NEW_LISTING,
    /**
     * A live listing that was reported or flagged (catalog.product.flagged): allow keeps it live (dismisses the
     * flags), remove takes it down in catalog.
     */
    LIVE_LISTING,
    /**
     * A customer review of a completed booking (bookings' booking.review.submitted): allow publishes it, remove
     * rejects it, both through bookings-service ({@code POST /v1/reviews/{bookingId}/approve|reject}).
     */
    SERVICE_REVIEW,
    /**
     * A verified-purchase review of a product (catalog's catalog.review.submitted when it failed the automatic
     * checks, or catalog.review.reported for a published review someone reported): allow publishes it (or keeps a
     * reported one public), remove rejects it (pending) or takes it down (published), through catalog-service
     * ({@code POST /v1/product-reviews/{id}/approve|reject|remove}).
     */
    PRODUCT_REVIEW;

    /** Listing items (new or live) as opposed to reviews. */
    public boolean listing() {
        return this != SERVICE_REVIEW && this != PRODUCT_REVIEW;
    }

    /** Customer reviews of bookings or of products. */
    public boolean review() {
        return !listing();
    }
}
