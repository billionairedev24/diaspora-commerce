package com.afrotransact.retail.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.domain.SupplierEdiProfile;

/** EDI trading-partner settings per supplier. */
public interface SupplierEdiProfileRepository {

    Optional<SupplierEdiProfile> find(UUID supplierId);

    /** Inserts or replaces the profile (optimistic lock on {@code version} when it exists). */
    SupplierEdiProfile save(SupplierEdiProfile profile);
}
