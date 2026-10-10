package com.afrotransact.offers.domain;

import java.util.List;

/** Outcome of applying a coupon to a cart: either a discount with its allocation, or a rejection reason. */
public record CouponEvaluation(boolean applicable, CouponRejection rejection, String message,
                               long eligibleSubtotalMinor, long discountMinor, long shippingDiscountMinor,
                               List<LineDiscount> lines) {

    public CouponEvaluation {
        lines = List.copyOf(lines);
    }

    public static CouponEvaluation rejected(CouponRejection rejection, String message) {
        return new CouponEvaluation(false, rejection, message, 0, 0, 0, List.of());
    }

    public long totalDiscountMinor() {
        return discountMinor + shippingDiscountMinor;
    }
}
