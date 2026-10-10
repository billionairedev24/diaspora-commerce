package com.afrotransact.taxreturns.application;

import com.afrotransact.taxreturns.domain.returns.SellerReturnPolicy;

/** Sellers' own return policies (window, restocking fee, return address). */
public interface ReturnPolicyService {

    /** The seller's policy, or the platform defaults when none is set. */
    SellerReturnPolicy get(String orgId);

    /** Creates or replaces the seller's policy (returns:manage for the organisation, or staff). */
    SellerReturnPolicy put(SellerReturnPolicy policy);
}
