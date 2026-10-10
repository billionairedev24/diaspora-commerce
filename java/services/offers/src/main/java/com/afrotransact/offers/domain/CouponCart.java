package com.afrotransact.offers.domain;

import java.util.List;

/**
 * The cart or order a coupon is evaluated against.
 *
 * @param shippingMinor shipping total of the cart (discounted by FREE_SHIPPING coupons)
 * @param firstOrder    the customer has no previous order (asserted by orders-service / the BFF)
 */
public record CouponCart(String currency, List<CouponLine> lines, long shippingMinor, boolean firstOrder) {

    public CouponCart {
        lines = List.copyOf(lines);
        if (shippingMinor < 0) {
            throw new OfferRuleViolation("Shipping cannot be negative");
        }
    }
}
