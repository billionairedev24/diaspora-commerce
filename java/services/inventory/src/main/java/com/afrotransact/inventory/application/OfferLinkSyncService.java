package com.afrotransact.inventory.application;

import java.time.Instant;
import java.util.UUID;

/** Keeps the projection of Fulfilled-by-AfroTransact offers from offers.offer.* events (stranded FBA stock, S067). */
public interface OfferLinkSyncService {

    /** Applies one offer event (inbox-deduplicated); offers that are not Fulfilled by AfroTransact are dropped. */
    void offerChanged(UUID eventId, OfferState offer);

    /**
     * The offer as the event carries it.
     *
     * @param inventorySku the FBA stock the offer sells (offers ≥ S257); null for older events
     */
    record OfferState(UUID offerId, String sellerOrgId, String atin, String inventorySku, String fulfilment,
                      String status, boolean productLive, Instant occurredAt) {
    }
}
