package com.afrotransact.offers.domain;

import java.time.Instant;

/** Coupon rules: what a valid coupon looks like, and how much it takes off a cart. */
public interface CouponPolicy {

    /** Throws {@link OfferRuleViolation} listing every failing field of a new coupon. */
    void validate(Coupon coupon);

    /** Applies the coupon to the cart; never throws for business reasons (returns a rejection instead). */
    CouponEvaluation evaluate(Coupon coupon, CouponCart cart, CouponUsage usage, Instant now);

    /** Whether the coupon's scope and funding cover this line (ignores limits, dates and minimum spend). */
    boolean appliesTo(Coupon coupon, CouponLine line);
}
