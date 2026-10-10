package com.afrotransact.offers.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A discount code.
 *
 * @param value            whole percent for PERCENT, minor units for FIXED, 0 for FREE_SHIPPING
 * @param maxDiscountMinor optional cap for PERCENT coupons
 * @param totalLimit       maximum redemptions overall, 0 = unlimited
 * @param perCustomerLimit maximum redemptions per customer, 0 = unlimited
 * @param clipRequired     the customer must clip the coupon (product page / coupons page) before it applies
 * @param fundedByOrg      seller org paying a SELLER-funded coupon, null for PLATFORM
 * @param endsAt           exclusive end of the validity window, null = open-ended
 */
public record Coupon(UUID id, String code, String description, CouponType type, long value, String currency,
                     Long maxDiscountMinor, CouponScope scope, String scopeRef, long minSpendMinor, int totalLimit,
                     int perCustomerLimit, boolean clipRequired, CouponFunding funding, String fundedByOrg,
                     Instant startsAt, Instant endsAt, CouponStatus status, long redeemedCount, String createdBy,
                     Instant createdAt, Instant updatedAt, long version) {

    public Coupon {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
    }

    public boolean inWindow(Instant now) {
        return !now.isBefore(startsAt) && (endsAt == null || now.isBefore(endsAt));
    }

    public Coupon withStatus(CouponStatus next, Instant now) {
        if (status == CouponStatus.EXPIRED && next != CouponStatus.EXPIRED) {
            throw new IllegalStateException("An expired coupon cannot be reactivated");
        }
        return new Coupon(id, code, description, type, value, currency, maxDiscountMinor, scope, scopeRef,
                minSpendMinor, totalLimit, perCustomerLimit, clipRequired, funding, fundedByOrg, startsAt, endsAt,
                next, redeemedCount, createdBy, createdAt, now, version);
    }

    public Coupon withRedeemedDelta(long delta, Instant now) {
        return new Coupon(id, code, description, type, value, currency, maxDiscountMinor, scope, scopeRef,
                minSpendMinor, totalLimit, perCustomerLimit, clipRequired, funding, fundedByOrg, startsAt, endsAt,
                status, Math.max(0, redeemedCount + delta), createdBy, createdAt, now, version);
    }
}
