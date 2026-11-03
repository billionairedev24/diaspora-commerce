package com.afrotransact.seller.domain;

/** A version is edited as a draft, published (the plan's current version) and superseded by the next one. */
public enum PlanVersionStatus {
    DRAFT,
    PUBLISHED,
    SUPERSEDED
}
