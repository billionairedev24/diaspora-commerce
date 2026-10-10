package com.afrotransact.offers.domain;

/**
 * Who pays the discount, recorded on every redemption for settlement: PLATFORM-funded coupons are booked as
 * AfroTransact marketing expense; SELLER-funded ones reduce that seller's payout.
 */
public enum CouponFunding {
    PLATFORM,
    SELLER
}
