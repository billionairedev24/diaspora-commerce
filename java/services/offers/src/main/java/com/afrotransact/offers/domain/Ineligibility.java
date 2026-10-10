package com.afrotransact.offers.domain;

/**
 * Why an offer cannot win the buy box. The first group also removes the offer from the "other sellers" listing;
 * {@link #CONDITION_NOT_NEW} and {@link #SELLER_HEALTH_BELOW_THRESHOLD} only keep it out of the buy box.
 */
public enum Ineligibility {
    NOT_ACTIVE(true),
    PRODUCT_NOT_LIVE(true),
    OUT_OF_STOCK(true),
    SELLER_LISTINGS_HIDDEN(true),
    SELLER_RESTRICTED(true),
    CONDITION_NOT_NEW(false),
    SELLER_HEALTH_BELOW_THRESHOLD(false);

    private final boolean unlisted;

    Ineligibility(boolean unlisted) {
        this.unlisted = unlisted;
    }

    /** True when the reason also hides the offer from the product page. */
    public boolean unlisted() {
        return unlisted;
    }
}
