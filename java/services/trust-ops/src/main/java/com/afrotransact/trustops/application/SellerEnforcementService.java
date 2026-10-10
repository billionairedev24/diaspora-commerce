package com.afrotransact.trustops.application;

import java.util.List;

import com.afrotransact.trustops.domain.SellerEnforcement;
import com.afrotransact.trustops.domain.SuspensionReason;

/**
 * S120 / S228 suspend and reinstate. Suspension places seller-service's suspension hold
 * ({@code POST /v1/orgs/{orgId}/suspend}: payouts paused, listings hidden in catalog, offers, bookings and search,
 * organisation SUSPENDED) and restricts the seller in the buy box (offers {@code restricted=true}); reinstatement
 * lifts both, leaving unrelated Stripe compliance payout holds in place. Calling again retries pending steps.
 * AfroTransact Retail is exempt (422 {@code trustops.retail-exempt}): it is the merchant of record and has no payouts.
 */
public interface SellerEnforcementService {

    SellerEnforcement suspend(String orgId, SuspensionReason reason, String note);

    SellerEnforcement reinstate(String orgId, String note);

    /** Standing of one seller (orgs:read). */
    SellerEnforcement standing(String orgId);

    /** Suspended sellers (orgs:read). */
    List<SellerEnforcement> suspended();
}
