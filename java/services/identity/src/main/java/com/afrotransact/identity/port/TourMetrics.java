package com.afrotransact.identity.port;

/** Prometheus counters of guided-tour use. Deliberately no tour id and no user tag (low cardinality, no personal data). */
public interface TourMetrics {

    /** event: started, completed, dismissed or reset. */
    void event(String event);
}
