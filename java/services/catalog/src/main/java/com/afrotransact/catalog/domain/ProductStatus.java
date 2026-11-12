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
    TAKEN_DOWN,
    /**
     * A no-barcode item in a brand-gated category, or of a brand known to carry barcodes, waits for staff to accept that
     * it has none (S387). Not live; approval moves it to IN_REVIEW.
     */
    PENDING_EXEMPTION,
    /** Merged into another page as a duplicate (S386): its ATIN redirects to the page that survived. Never live. */
    MERGED
}
