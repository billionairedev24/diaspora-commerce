package com.afrotransact.orders.port;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.afrotransact.orders.domain.SellerSelfShipping;

/** The sellers' own flat shipping charges (only read while the staff switch is on). */
public interface SellerSelfShippingRepository {

    Optional<SellerSelfShipping> find(String sellerOrgId);

    /** The records of these sellers (those that have one). */
    List<SellerSelfShipping> findAll(Collection<String> sellerOrgIds);

    /** Creates or replaces the record (the caller has already bumped the version). */
    void save(SellerSelfShipping record);
}
