package com.afrotransact.catalog.port;

/** Counters of the match service. They carry the outcome only, never a barcode or a seller. */
public interface MatchMetrics {

    /** One answered lookup: MATCH, NO_MATCH, INVALID or RESTRICTED. */
    void matched(String result);

    /**
     * A candidate search that was answered from the database instead of the search index: reason is {@code search_error}
     * (the index could not be asked), {@code search_empty} (it answered nothing) or {@code both_failed} (the database
     * could not answer either, so the seller sees "search unavailable").
     */
    void candidatesFallback(String reason);
}
