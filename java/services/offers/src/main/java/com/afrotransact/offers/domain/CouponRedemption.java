package com.afrotransact.offers.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One use of a coupon on an order, with who funds it (for settlement) and the per-line allocation.
 *
 * @param fingerprint hash of the redeem request; a reused Idempotency-Key with a different request is refused
 */
public record CouponRedemption(UUID id, UUID couponId, String code, String customerId, String orderId,
                               String idempotencyKey, String fingerprint, String currency, long discountMinor,
                               long shippingDiscountMinor, CouponFunding funding, String fundedByOrg,
                               List<LineDiscount> lines, RedemptionStatus status, Instant createdAt,
                               Instant releasedAt) {

    public CouponRedemption {
        lines = List.copyOf(lines);
    }

    public CouponRedemption release(Instant now) {
        return new CouponRedemption(id, couponId, code, customerId, orderId, idempotencyKey, fingerprint, currency,
                discountMinor, shippingDiscountMinor, funding, fundedByOrg, lines, RedemptionStatus.RELEASED,
                createdAt, now);
    }
}
