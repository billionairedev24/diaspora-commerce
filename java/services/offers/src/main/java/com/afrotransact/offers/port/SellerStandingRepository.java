package com.afrotransact.offers.port;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.offers.domain.SellerStanding;

/** Seller names, performance inputs and enforcement flags. */
public interface SellerStandingRepository {

    Optional<SellerStanding> find(String orgId);

    Map<String, SellerStanding> findAll(Collection<String> orgIds);

    /** Insert or replace. */
    SellerStanding save(SellerStanding standing);
}
