package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.domain.Coupon;
import com.afrotransact.offers.domain.CouponFunding;
import com.afrotransact.offers.domain.CouponStatus;

/** Coupons and promotions: management, clip-to-apply, validation for carts and idempotent redemption for orders. */
public interface CouponService {

    Coupon create(CreateCouponCommand command);

    /** Staff with coupons:platform:write see every coupon; sellers see their own. */
    List<Coupon> list(CouponFunding funding, String fundedByOrg, CouponStatus status);

    Coupon get(UUID id);

    Coupon changeStatus(UUID id, CouponStatus status);

    /** The signed-in customer clips a coupon (idempotent). */
    Coupon clip(String code);

    /** Active clip-to-apply coupons that cover at least one offer of the ATIN (product page "Clip coupon"). */
    List<Coupon> clippable(String atin, String currency);

    /** Prices a coupon against a cart without recording anything. */
    CouponQuote validate(CouponCartCommand command);

    /**
     * Records the use of a coupon on an order (orders-service). Safe to retry with the same Idempotency-Key; a
     * different request under the same key is refused with 409.
     */
    RedemptionResult redeem(String idempotencyKey, CouponCartCommand command);

    /** Gives the use back when the order is cancelled or payment fails (idempotent). */
    RedemptionResult release(UUID redemptionId);
}
