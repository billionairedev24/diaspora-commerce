package com.afrotransact.payments.domain;

/** Where a tier is in its life. Only published tiers can be joined; retired tiers keep the members they have. */
public enum TierStatus {
    DRAFT,
    PUBLISHED,
    RETIRED
}
