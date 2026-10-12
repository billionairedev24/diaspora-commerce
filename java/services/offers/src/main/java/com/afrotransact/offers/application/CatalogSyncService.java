package com.afrotransact.offers.application;

import java.util.UUID;

/** Reacts to catalog.product.* events (inbox de-duplicated). */
public interface CatalogSyncService {

    /** catalog.product.created: creates the creating seller's offers on the new variants (not live until approved). */
    void productCreated(UUID eventId, ProductSnapshot product);

    /** catalog.product.approved|resumed (live) or rejected|paused (not live): flips every offer on the page. */
    void productStatusChanged(UUID eventId, String eventType, ProductSnapshot product);

    /**
     * catalog.product.terms_changed (S307): the seller changed price, quantity, "was" price, handling time or
     * ship-from through the product form or a bulk update. Updates the seller's open offers of the product's variants
     * (seller-fulfilled quantity only) and creates offers for variants that have none yet.
     */
    void termsChanged(UUID eventId, ProductSnapshot product);
}
