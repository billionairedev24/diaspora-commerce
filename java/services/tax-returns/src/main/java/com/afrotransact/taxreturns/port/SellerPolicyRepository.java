package com.afrotransact.taxreturns.port;

import java.util.Optional;

import com.afrotransact.taxreturns.domain.returns.SellerReturnPolicy;

/** Sellers' own return policies. */
public interface SellerPolicyRepository {

    Optional<SellerReturnPolicy> find(String orgId);

    SellerReturnPolicy upsert(SellerReturnPolicy policy);
}
