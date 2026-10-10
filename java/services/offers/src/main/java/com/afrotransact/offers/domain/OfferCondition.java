package com.afrotransact.offers.domain;

/** Item condition of an offer. Only {@link #NEW} offers compete for the buy box; others are listed as other offers. */
public enum OfferCondition {
    NEW,
    RENEWED,
    USED_LIKE_NEW,
    USED_VERY_GOOD,
    USED_GOOD,
    USED_ACCEPTABLE
}
