package com.afrotransact.catalog.domain;

/** Product moderation lifecycle. */
public enum ProductStatus {
    /** Submitted, waiting for listing moderation. */
    IN_REVIEW,
    /** Built against a requested category; publishes once the category is approved. */
    PENDING_CATEGORY_APPROVAL,
    LIVE,
    REJECTED,
    PAUSED,
    /** A live (or paused) listing removed by Trust and Safety; hidden publicly until staff restore it. */
    TAKEN_DOWN
}
