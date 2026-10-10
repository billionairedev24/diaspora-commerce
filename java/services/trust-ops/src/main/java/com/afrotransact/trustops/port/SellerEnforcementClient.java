package com.afrotransact.trustops.port;

import com.afrotransact.trustops.domain.SuspensionReason;

/**
 * seller-service suspension hold (contracts/openapi/seller.yaml, permission sellers:enforce):
 * {@code POST /v1/orgs/{orgId}/suspend} pauses payouts, hides the organisation's listings (catalog, offers, bookings,
 * search) and sets it SUSPENDED; {@code POST /v1/orgs/{orgId}/reinstate} lifts only that hold, so unrelated Stripe
 * compliance payout holds stay. Both are idempotent in seller-service.
 */
public interface SellerEnforcementClient {

    void suspend(String orgId, SuspensionReason reason, String note);

    void reinstate(String orgId, String note);
}
