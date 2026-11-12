package com.afrotransact.catalog.port;

/** Counters of the match service. They carry the outcome only, never a barcode or a seller. */
public interface MatchMetrics {

    /** One answered lookup: MATCH, NO_MATCH, INVALID or RESTRICTED. */
    void matched(String result);
}
