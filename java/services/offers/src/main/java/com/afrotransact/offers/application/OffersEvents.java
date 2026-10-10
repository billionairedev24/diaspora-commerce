package com.afrotransact.offers.application;

/** Event types published by offers-service on {@code at.offers.events} (contracts/asyncapi/fragments/offers.yaml). */
public final class OffersEvents {

    /** Every offers event goes to one topic; offer and buy box events are keyed by ATIN, coupons by coupon id. */
    public static final String AGGREGATE = "offers";

    public static final String OFFER_CREATED = "offers.offer.created";
    public static final String OFFER_UPDATED = "offers.offer.updated";
    public static final String OFFER_WITHDRAWN = "offers.offer.withdrawn";
    public static final String BUYBOX_CHANGED = "offers.buybox.changed";
    public static final String COUPON_CREATED = "offers.coupon.created";
    public static final String COUPON_UPDATED = "offers.coupon.updated";
    public static final String COUPON_REDEEMED = "offers.coupon.redeemed";
    public static final String COUPON_RELEASED = "offers.coupon.released";

    private OffersEvents() {
    }
}
