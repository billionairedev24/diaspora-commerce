package com.afrotransact.offers.domain;

/** Coupon lifecycle controlled by its owner (staff for platform coupons, the seller for seller coupons). */
public enum CouponStatus {
    ACTIVE,
    PAUSED,
    EXPIRED
}
