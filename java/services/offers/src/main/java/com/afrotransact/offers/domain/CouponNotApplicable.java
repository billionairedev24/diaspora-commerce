package com.afrotransact.offers.domain;

/** A redeem request whose coupon does not apply to the order (HTTP 422 with the rejection reason). */
public class CouponNotApplicable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final CouponRejection rejection;

    public CouponNotApplicable(CouponRejection rejection, String message) {
        super(message);
        this.rejection = rejection;
    }

    public CouponRejection rejection() {
        return rejection;
    }
}
