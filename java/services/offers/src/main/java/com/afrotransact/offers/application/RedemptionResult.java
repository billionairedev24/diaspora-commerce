package com.afrotransact.offers.application;

import com.afrotransact.offers.domain.CouponRedemption;

/** A redemption and whether it was replayed from an earlier request with the same Idempotency-Key. */
public record RedemptionResult(CouponRedemption redemption, boolean replayed) {
}
