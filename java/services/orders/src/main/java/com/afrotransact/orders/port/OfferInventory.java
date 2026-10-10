package com.afrotransact.orders.port;

import java.util.Optional;

/**
 * The inventory SKU of a Fulfilled-by-AfroTransact offer: offers-service {@code GET /v1/offers/{id}} with the
 * orders-service token ({@code offers:read}) → {@code inventorySku}. FBA stock is received and reserved under it (the
 * seller's AT-FN code by default; AfroTransact Retail's own SKU for first-party stock), not under the variant ATIN.
 * Throws {@link UpstreamFailure} (5xx / I/O retryable).
 */
public interface OfferInventory {

    /** Empty when the offer is unknown (404) or names no inventory SKU. */
    Optional<String> inventorySku(String offerId);
}
