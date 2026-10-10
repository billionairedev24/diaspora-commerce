package com.afrotransact.catalog.application;

/**
 * Hides and restores an organisation's products when seller-service enforces compliance (topic
 * {@code at.seller-compliance.events}). Hidden products disappear from the public catalog API, and catalog publishes
 * {@code catalog.product.hidden} / {@code catalog.product.restored} for each of them so search and other read models
 * follow. Idempotent per event id; older decisions never override newer ones.
 */
public interface ListingVisibilityService {

    /** @return the number of product events published (0 for a redelivered, stale or no-op event) */
    int hide(ListingEnforcement event);

    /** @return the number of product events published (0 for a redelivered, stale or no-op event) */
    int restore(ListingEnforcement event);

    /** Whether the organisation's listings are currently hidden. */
    boolean hidden(String orgId);
}
