package com.afrotransact.offers.api;

import java.util.List;
import java.util.Optional;

/**
 * What the orders module asks offers in-process at checkout: coupons ({@code POST /v1/coupons/validate},
 * {@code /v1/coupons/redemptions}, {@code /v1/coupons/redemptions/{id}/release}) and two offer reads
 * ({@code GET /v1/offers/{id}}, {@code GET /v1/public/offers/{id}}). Every call runs under an in-process caller principal;
 * offers' own permission checks apply and a caller without the scope is refused with
 * {@code com.afrotransact.module.ModuleApiException} status 403. Failures arrive with the endpoint's status and code
 * ({@code offers.coupon-not-applicable} 422, {@code offers.idempotency-key-reused} 409, ...).
 */
public interface CheckoutOffersApi {

    /** Prices a coupon against a cart; nothing is recorded; an unusable coupon is {@code applicable=false}, never an error. */
    QuoteInfo validate(CouponRequest request);

    /** Redeems a coupon on an order; a replay with the same key returns the original redemption. */
    RedemptionInfo redeem(String idempotencyKey, CouponRequest request);

    /** Releases a redemption (order cancelled or payment failed); idempotent. */
    RedemptionInfo release(String redemptionId);

    /** @return the offer's inventory SKU, empty when the offer is unknown or has none (HTTP 404) */
    Optional<String> inventorySku(String offerId);

    /** @return the id of the seller's pickup location of an offer, empty when the offer is unknown or has none */
    Optional<String> shipFromLocationId(String offerId);

    record CouponRequest(String code, String customerId, String orderId, String currency, List<CouponLine> lines,
                         long shippingMinor, boolean firstOrder) {

        public CouponRequest {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    record CouponLine(String offerId, int quantity) {
    }

    record DiscountLine(String lineRef, String sellerOrgId, long discountMinor) {
    }

    /** @param rejection the stable rejection code (NOT_FOUND, EXPIRED, BELOW_MINIMUM_SPEND ...) when not applicable */
    record QuoteInfo(boolean applicable, String rejection, String message, long discountMinor,
                       long shippingDiscountMinor, long totalDiscountMinor, String funding, List<DiscountLine> lines) {

        public QuoteInfo {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    record RedemptionInfo(String id, String couponId, String code, String status, long discountMinor,
                            long shippingDiscountMinor, long totalDiscountMinor, String funding, String fundedByOrg,
                            List<DiscountLine> lines) {

        public RedemptionInfo {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }
}
