package com.afrotransact.catalog.domain;

/** Why a live listing is reported, flagged or taken down (same codes as the trust-ops moderation queue). */
public enum ListingReasonCode {
    PROHIBITED_ITEM,
    UNAPPROVED_HEALTH_CLAIM,
    RESTRICTED_INGREDIENT,
    COUNTERFEIT,
    IMAGE_POLICY,
    MISLEADING_CONTENT,
    LABELLING,
    SHIPPING_METHOD,
    DUPLICATE_LISTING,
    WRONG_CATEGORY,
    OTHER
}
