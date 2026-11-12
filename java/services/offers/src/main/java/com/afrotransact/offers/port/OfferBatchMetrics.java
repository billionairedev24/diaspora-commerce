package com.afrotransact.offers.port;

/** Counters of the batch offer endpoints. Tags are low cardinality: operation and outcome only. */
public interface OfferBatchMetrics {

    /** {@code operation} is UPSERT or PRICES_AND_STOCK; {@code outcome} is CREATED, UPDATED, UNCHANGED, HELD or FAILED. */
    void row(String operation, String outcome);
}
