package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.domain.CouponRedemption;
import com.afrotransact.offers.domain.LineDiscount;

/**
 * Payload of {@code offers.coupon.redeemed|released}. payments-service books PLATFORM-funded discounts as marketing
 * expense and deducts SELLER-funded ones (per line allocation) from that seller's payout.
 */
public record RedemptionEventPayload(UUID redemptionId, UUID couponId, String code, String customerId, String orderId,
                                     String currency, long discountMinor, long shippingDiscountMinor, String funding,
                                     String fundedByOrg, List<LineDiscount> lines, String status) {

    static RedemptionEventPayload of(CouponRedemption r) {
        return new RedemptionEventPayload(r.id(), r.couponId(), r.code(), r.customerId(), r.orderId(), r.currency(),
                r.discountMinor(), r.shippingDiscountMinor(), r.funding().name(), r.fundedByOrg(), r.lines(),
                r.status().name());
    }
}
