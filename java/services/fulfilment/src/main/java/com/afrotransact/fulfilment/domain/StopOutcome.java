package com.afrotransact.fulfilment.domain;

/** Where a stop goes after a reported problem. */
public enum StopOutcome {
    /** Retry later on this route. */
    RETRY,
    /** The attempt failed; packages go back (FC or pickup point) or are reported missing. */
    FAILED,
    /** The stop stays open (accepted with damage, or waiting at a seller). */
    CONTINUE,
    /** Seller pickup skipped; the seller reschedules. */
    SKIPPED
}
