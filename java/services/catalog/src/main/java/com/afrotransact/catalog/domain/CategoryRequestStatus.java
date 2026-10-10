package com.afrotransact.catalog.domain;

/** Lifecycle of a seller's new-category request. */
public enum CategoryRequestStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED
}
