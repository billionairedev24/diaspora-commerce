package com.afrotransact.trustops.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.trustops.domain.SellerEnforcement;

/** Seller standing (ACTIVE / SUSPENDED) with upstream step flags. */
public interface SellerEnforcementRepository {

    Optional<SellerEnforcement> find(String orgId);

    /** Inserts a new row or updates with optimistic locking. */
    SellerEnforcement save(SellerEnforcement enforcement);

    List<SellerEnforcement> suspended();
}
