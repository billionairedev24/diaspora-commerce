package com.afrotransact.offers.application;

import java.util.UUID;

/** Reacts to catalog.product.* events (inbox de-duplicated). */
public interface CatalogSyncService {

    /** catalog.product.created: creates the creating seller's offers on the new variants (not live until approved). */
    void productCreated(UUID eventId, ProductSnapshot product);

    /** catalog.product.approved|resumed (live) or rejected|paused (not live): flips every offer on the page. */
    void productStatusChanged(UUID eventId, String eventType, ProductSnapshot product);
}
