package com.afrotransact.inventory.domain;

/** Where the exchange rate of a lot's cost came from. */
public enum RateSource {
    /** US dollars: rate 1, nothing to convert. */
    BASE,
    /** The receiver (or the system that sent the receipt) entered the rate. */
    ENTERED,
    /** Taken from the staff-managed table of rates: the latest on or before the receiving date. */
    TABLE
}
