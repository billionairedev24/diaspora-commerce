package com.afrotransact.bookings.domain;

/** What a no-show costs the customer. */
public enum NoShowRule {
    /** The deposit is kept; no balance is charged. */
    DEPOSIT,
    /** The full price is charged. */
    FULL
}
