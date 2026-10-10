package com.afrotransact.offers.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.domain.Coupon;
import com.afrotransact.offers.domain.CouponFunding;
import com.afrotransact.offers.domain.CouponRedemption;
import com.afrotransact.offers.domain.CouponStatus;

/** Coupons, clips and redemptions. */
public interface CouponRepository {

    Coupon insert(Coupon coupon);

    Coupon update(Coupon coupon);

    Optional<Coupon> findById(UUID id);

    Optional<Coupon> findByCode(String code);

    /** Reads the coupon with a row lock held until commit (serialises redemptions of one coupon). */
    Optional<Coupon> lockByCode(String code);

    Optional<Coupon> lockById(UUID id);

    List<Coupon> list(CouponFunding funding, String fundedByOrg, CouponStatus status);

    /** Active coupons that require clipping and are inside their validity window at {@code now}. */
    List<Coupon> findClippable(String currency, Instant now);

    /** Records the clip; false when already clipped. */
    boolean clip(UUID couponId, String customerId, Instant now);

    boolean clipped(UUID couponId, String customerId);

    /** Redemptions in REDEEMED status overall and for the customer. */
    long redeemedCount(UUID couponId);

    long redeemedCount(UUID couponId, String customerId);

    Optional<CouponRedemption> findRedemption(UUID id);

    Optional<CouponRedemption> findRedemptionByKey(String idempotencyKey);

    CouponRedemption insertRedemption(CouponRedemption redemption);

    CouponRedemption updateRedemption(CouponRedemption redemption);
}
