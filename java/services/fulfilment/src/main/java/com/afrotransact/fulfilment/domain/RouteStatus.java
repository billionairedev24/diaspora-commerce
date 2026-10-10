package com.afrotransact.fulfilment.domain;

/** Lifecycle of a delivery route. */
public enum RouteStatus {
    /** Built by dispatch, no driver yet. */
    PLANNED,
    /** Driver (and vehicle) assigned; inspection and load-out happen now. */
    ASSIGNED,
    /** Route started: stops are worked. */
    IN_PROGRESS,
    /** Shift ended: every stop resolved, failed packages scanned back in. */
    COMPLETED,
    CANCELLED;

    public boolean open() {
        return this == PLANNED || this == ASSIGNED || this == IN_PROGRESS;
    }
}
