package com.afrotransact.bookings.domain;

/** Moderation state of a review: only {@link #APPROVED} reviews are public and count in ratings. */
public enum ReviewStatus {
    /** Submitted by the customer; waiting in trust-ops' moderation queue. */
    PENDING,
    /** Public: listed on the provider and service pages and averaged in ratings. */
    APPROVED,
    /** Removed by moderation; never public (the customer and the provider still see it). */
    REJECTED
}
