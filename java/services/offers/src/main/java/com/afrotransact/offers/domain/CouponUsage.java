package com.afrotransact.offers.domain;

/** Redemptions so far (excluding released ones) and whether this customer clipped the coupon. */
public record CouponUsage(long totalRedeemed, long customerRedeemed, boolean clipped) {
}
