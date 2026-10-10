package com.afrotransact.retail.port;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.domain.ListingStatus;
import com.afrotransact.retail.domain.RetailItem;

/** Persistence port for the 1P product master. */
public interface ItemRepository {

    RetailItem insert(RetailItem item);

    RetailItem update(RetailItem item);

    Optional<RetailItem> findById(UUID id);

    Optional<RetailItem> findBySku(String sku);

    Optional<RetailItem> findByAtin(String atin);

    List<RetailItem> findByIds(Collection<UUID> ids);

    List<RetailItem> findBySkus(Collection<String> skus);

    Page<RetailItem> page(String query, UUID supplierId, ListingStatus listing, int page, int size);
}
