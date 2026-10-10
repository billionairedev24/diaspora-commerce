package com.afrotransact.offers.application;

import java.time.Instant;

import com.afrotransact.offers.domain.CouponFunding;
import com.afrotransact.offers.domain.CouponScope;
import com.afrotransact.offers.domain.CouponType;

/**
 * New coupon. PLATFORM funding needs {@code coupons:platform:write} (staff); SELLER funding needs
 * {@code coupons:write} and is always funded by the caller's organisation (staff may name {@code fundedByOrg}).
 */
public record CreateCouponCommand(String code, String description, CouponType type, long value, String currency,
                                  Long maxDiscountMinor, CouponScope scope, String scopeRef, long minSpendMinor,
                                  int totalLimit, int perCustomerLimit, boolean clipRequired, CouponFunding funding,
                                  String fundedByOrg, Instant startsAt, Instant endsAt) {
}
