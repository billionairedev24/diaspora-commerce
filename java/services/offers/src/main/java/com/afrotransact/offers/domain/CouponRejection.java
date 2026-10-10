package com.afrotransact.offers.domain;

/** Why a coupon does not apply to a cart. Codes are stable; the storefront maps them to copy. */
public enum CouponRejection {
    NOT_FOUND,
    NOT_ACTIVE,
    NOT_STARTED,
    EXPIRED,
    CURRENCY_MISMATCH,
    NOT_CLIPPED,
    TOTAL_LIMIT_REACHED,
    CUSTOMER_LIMIT_REACHED,
    FIRST_ORDER_ONLY,
    NO_ELIGIBLE_ITEMS,
    BELOW_MINIMUM_SPEND,
    NO_SHIPPING_TO_DISCOUNT
}
