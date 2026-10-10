package com.afrotransact.deliveryruns.port;

/** Gap-tolerant counters (window codes per prefix, order numbers per window). */
public interface PreorderSequences {

    /** Next value of {@code name}: {@code first} the first time, then one more each call (atomic). */
    int next(String name, int first);
}
