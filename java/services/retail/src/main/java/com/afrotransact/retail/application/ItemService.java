package com.afrotransact.retail.application;

import java.util.UUID;

import com.afrotransact.retail.domain.ListingStatus;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.port.Page;

/** 1P product master linked to catalog ATINs (S068). */
public interface ItemService {

    RetailItem create(ItemCommand command);

    RetailItem update(UUID id, ItemCommand command, Long expectedVersion);

    /** Links the catalog variant ATIN after checking catalog knows it as a live platform (1P) listing. */
    RetailItem linkAtin(UUID id, String atin, Long expectedVersion);

    RetailItem changeListing(UUID id, ListingStatus target, Long expectedVersion);

    RetailItem get(UUID id);

    Page<RetailItem> list(String query, UUID supplierId, ListingStatus listing, int page, int size);
}
