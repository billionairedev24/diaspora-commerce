package com.afrotransact.orders.port;

import java.util.List;

/**
 * offers-service coupons: price a coupon against the order ({@code POST /v1/coupons/validate}), redeem it once per
 * order ({@code POST /v1/coupons/redemptions}, Idempotency-Key = order id) and release the redemption when the order
 * fails or is cancelled ({@code POST /v1/coupons/redemptions/{id}/release}, idempotent). Prices, sellers and
 * categories are read from the offers by offers-service; only offer ids and quantities are sent.
 */
public interface CouponsClient {

    /** Never throws for a coupon that does not apply: {@link Quote#applicable()} is false with a rejection code. */
    Quote validate(Request request);

    /**
     * Redeems the coupon. The same key and body return the original redemption; a coupon that no longer applies is an
     * {@link UpstreamFailure} with code {@code offers.coupon-not-applicable} (status 422).
     */
    Redemption redeem(String idempotencyKey, Request request);

    /** Gives the redemption back (order voided or cancelled). Idempotent. */
    Redemption release(String redemptionId);

    /** The order as offers-service prices coupons. */
    record Request(String code, String customerId, String orderId, String currency, List<Line> lines,
                   long shippingMinor, boolean firstOrder) {

        public Request {
            lines = List.copyOf(lines);
        }
    }

    /** One offer and quantity. */
    record Line(String offerId, int quantity) {
    }

    /** Discount of one line; {@code lineRef} is the offer id. */
    record LineDiscount(String lineRef, String sellerOrgId, long discountMinor) {
    }

    /** Validation result. */
    record Quote(boolean applicable, String rejection, String message, long discountMinor,
                 long shippingDiscountMinor, long totalDiscountMinor, String funding, List<LineDiscount> lines) {

        public Quote {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** A redemption (REDEEMED or RELEASED). */
    record Redemption(String id, String couponId, String code, String status, long discountMinor,
                      long shippingDiscountMinor, long totalDiscountMinor, String funding, String fundedByOrg,
                      List<LineDiscount> lines) {

        public Redemption {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }
}
