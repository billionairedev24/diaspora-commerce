package com.afrotransact.fulfilment.domain;

/** Status of a route stop. */
public enum StopStatus {
    PENDING,
    ARRIVED,
    DONE,
    FAILED,
    /** Reattempt later on this route; must be resolved before the shift ends. */
    RETRY,
    /** Seller pickup skipped (or a drop-off whose packages never got picked up). */
    SKIPPED,
    /** Moved to another route by dispatch. */
    REASSIGNED,
    CANCELLED;

    /** Still needs the driver's attention before the shift can end. */
    public boolean unresolved() {
        return this == PENDING || this == ARRIVED || this == RETRY;
    }
}
