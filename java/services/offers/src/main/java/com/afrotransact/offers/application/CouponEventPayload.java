package com.afrotransact.offers.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.offers.domain.Coupon;

/** Payload of {@code offers.coupon.created|updated}. */
public record CouponEventPayload(UUID couponId, String code, String type, long value, String currency,
                                 Long maxDiscountMinor, String scope, String scopeRef, long minSpendMinor,
                                 int totalLimit, int perCustomerLimit, boolean clipRequired, String funding,
                                 String fundedByOrg, Instant startsAt, Instant endsAt, String status,
                                 long redeemedCount) {

    static CouponEventPayload of(Coupon c) {
        return new CouponEventPayload(c.id(), c.code(), c.type().name(), c.value(), c.currency(),
                c.maxDiscountMinor(), c.scope().name(), c.scopeRef(), c.minSpendMinor(), c.totalLimit(),
                c.perCustomerLimit(), c.clipRequired(), c.funding().name(), c.fundedByOrg(), c.startsAt(),
                c.endsAt(), c.status().name(), c.redeemedCount());
    }
}
