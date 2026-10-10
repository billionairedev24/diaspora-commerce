package com.afrotransact.offers.domain;

/**
 * What a coupon applies to. CATEGORY, SELLER and PRODUCT need a {@code scopeRef} (category id, seller org id,
 * ATIN). Seller-funded coupons additionally apply only to the funding seller's lines whatever the scope.
 */
public enum CouponScope {
    SITEWIDE,
    FIRST_ORDER,
    CATEGORY,
    SELLER,
    PRODUCT,
    FULFILLED_BY_AFROTRANSACT;

    public boolean needsRef() {
        return this == CATEGORY || this == SELLER || this == PRODUCT;
    }
}
