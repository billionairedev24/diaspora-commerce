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
    SERVICE_REVIEW;

    /** Listing items (new or live) as opposed to reviews. */
    public boolean listing() {
        return this != SERVICE_REVIEW;
    }
}
