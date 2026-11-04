package com.afrotransact.catalog.domain;

/** Where a product review stands: only {@link #PUBLISHED} reviews are public and count in the product's rating. */
public enum ReviewStatus {
    /** Failed the automatic checks (or was edited after a rejection); waits in trust-ops' moderation queue. */
    PENDING,
    /** Public: listed on the product page and averaged in its rating. */
    PUBLISHED,
    /** Moderation refused it before it was public; the customer sees why and may edit it. */
    REJECTED,
    /** Moderation took a published review down; the customer sees why and may edit it. */
    REMOVED,
    /** The customer deleted it; kept for the audit trail only. */
    DELETED;

    /** Public and counted in ratings. */
    public boolean published() {
        return this == PUBLISHED;
    }

    /** Waiting for or refused by moderation: the customer's edit goes back to moderation. */
    public boolean refused() {
        return this == REJECTED || this == REMOVED;
    }
}
