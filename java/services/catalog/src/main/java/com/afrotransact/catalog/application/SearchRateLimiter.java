package com.afrotransact.catalog.application;

/**
 * A sliding window of searches per organisation: keeps one seller from reading the catalogue through the add-product
 * search. Per instance and best effort; the gateway's own limits sit in front of it.
 */
public interface SearchRateLimiter {

    /** Counts one search of the organisation; false when it has made {@code limit} already in the last hour. */
    boolean tryAcquire(String orgId, int limit);
}
