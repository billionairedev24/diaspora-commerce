package com.afrotransact.catalog.application;

/**
 * Hides and restores an organisation's products when seller-service enforces compliance (topic
 * {@code at.seller-compliance.events}). Hidden products disappear from the public catalog API, and catalog publishes
 * {@code catalog.product.hidden} / {@code catalog.product.restored} for each of them so search and other read models
 * follow. Idempotent per event id; older decisions never override newer ones.
 *
 * <p>Large catalogs are processed in pages (S216): the decision itself commits first, then each page of products
 * (default 500) is republished in its own transaction together with the run's cursor, so a crash or redelivery resumes
 * after the last committed page and a page is never published twice. A newer decision for the same organisation
 * supersedes a run still in progress.
 */
public interface ListingVisibilityService {

    /** @return the number of product events published (0 for a redelivered, stale or no-op event) */
    int hide(ListingEnforcement event);

    /** @return the number of product events published (0 for a redelivered, stale or no-op event) */
    int restore(ListingEnforcement event);

    /** Whether the organisation's listings are currently hidden. */
    boolean hidden(String orgId);

    /**
     * Continues runs left unfinished by a crashed or restarted pod.
     *
     * @return the number of product events published
     */
    int resumeStalled();
}
