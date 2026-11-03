package com.afrotransact.seller.domain;

/** A plan is drafted, then published ({@code ACTIVE}); a retired plan takes no new subscribers but keeps its own. */
public enum PlanStatus {
    DRAFT,
    ACTIVE,
    RETIRED
}
