package com.afrotransact.fulfilment.domain;

/** Status of a package on a route. */
public enum PackageStatus {
    /** Waiting for load-out (FC) or pickup (seller). */
    EXPECTED,
    /** Scanned at FC load-out. */
    LOADED,
    /** Collected at a seller pickup. */
    PICKED_UP,
    /** Not collected at the seller (skipped or missing from the handover); the seller reschedules. */
    NOT_PICKED_UP,
    DELIVERED,
    /** Delivery failed; must be scanned back in at the FC at end of shift. */
    FAILED,
    /** Going to a pickup point; scanned back in at end of shift. */
    TO_PICKUP_POINT,
    /** Not found on board; dispatch investigates. */
    MISSING,
    /** Scanned back in at the FC. */
    RETURNED,
    /** Moved to another route before it was on board. */
    REASSIGNED,
    CANCELLED;

    public boolean onBoard() {
        return this == LOADED || this == PICKED_UP;
    }

    public boolean awaitingReturnScan() {
        return this == FAILED || this == TO_PICKUP_POINT;
    }

    public boolean active() {
        return this != REASSIGNED && this != CANCELLED;
    }
}
