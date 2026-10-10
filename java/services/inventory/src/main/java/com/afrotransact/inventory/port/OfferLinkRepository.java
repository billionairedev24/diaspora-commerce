package com.afrotransact.inventory.port;

import java.time.Instant;
import java.util.UUID;

/** Projection of offers-service offers on FBA stock (offers.offer.* events), for stranded-stock flags (S067). */
public interface OfferLinkRepository {

    /**
     * Stores the offer's current state; Fulfilled-by-AfroTransact offers only (others are removed).
     *
     * @param live the offer is ACTIVE and its product live
     */
    void upsert(UUID offerId, String ownerOrg, String sku, String atin, String status, boolean live,
                boolean fulfilledByAfroTransact, Instant at);
}
