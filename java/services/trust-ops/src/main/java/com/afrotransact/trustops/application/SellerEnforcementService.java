package com.afrotransact.trustops.application;

import java.util.List;

import com.afrotransact.trustops.domain.SellerEnforcement;
import com.afrotransact.trustops.domain.SuspensionReason;

/**
 * S120 suspend and reinstate. Suspension holds payouts (seller-service compliance hold) and restricts the seller in
 * the buy box (offers {@code restricted=true}); reinstatement releases both. Calling again retries pending steps.
 */
public interface SellerEnforcementService {

    SellerEnforcement suspend(String orgId, SuspensionReason reason, String note);

    SellerEnforcement reinstate(String orgId, String note);

    /** Standing of one seller (orgs:read). */
    SellerEnforcement standing(String orgId);

    /** Suspended sellers (orgs:read). */
    List<SellerEnforcement> suspended();
}
