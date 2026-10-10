package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.domain.CouponFunding;
import com.afrotransact.offers.domain.CouponRejection;
import com.afrotransact.offers.domain.LineDiscount;

/** Result of validating a coupon against a cart (no usage is recorded). */
public record CouponQuote(String code, UUID couponId, boolean applicable, CouponRejection rejection, String message,
                          String currency, long eligibleSubtotalMinor, long discountMinor, long shippingDiscountMinor,
                          CouponFunding funding, String fundedByOrg, List<LineDiscount> lines) {

    public CouponQuote {
        lines = List.copyOf(lines);
    }
}
