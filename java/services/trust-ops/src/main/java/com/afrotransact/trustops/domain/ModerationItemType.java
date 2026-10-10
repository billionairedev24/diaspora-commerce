package com.afrotransact.trustops.domain;

/** What a moderation queue item is about. */
public enum ModerationItemType {
    /** A new submission waiting for review (catalog.product.created, IN_REVIEW): allow approves, remove rejects. */
    NEW_LISTING,
    /**
     * A live listing that was reported or flagged (catalog.product.flagged): allow keeps it live (dismisses the
     * flags), remove takes it down in catalog.
     */
    LIVE_LISTING
}
