package com.afrotransact.orders.port;

import java.time.Instant;
import java.util.Optional;

/** The coupon redeemed for an order (one per order). */
public interface OrderCouponRepository {

    /** Stores the redemption; a second save for the same order is ignored (idempotent persistence). */
    void save(OrderCoupon coupon);

    Optional<OrderCoupon> find(String orderId);

    /** Marks the redemption released; false when it already was. */
    boolean markReleased(String orderId, Instant at);

    /**
     * A redeemed coupon.
     *
     * @param status REDEEMED or RELEASED
     */
    record OrderCoupon(String orderId, String code, String redemptionId, long discountMinor,
                       long shippingDiscountMinor, String funding, String fundedByOrg, String status,
                       Instant createdAt, Instant releasedAt) {

        public boolean released() {
            return "RELEASED".equals(status);
        }
    }
}
