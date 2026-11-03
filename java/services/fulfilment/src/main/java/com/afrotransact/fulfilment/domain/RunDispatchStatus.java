package com.afrotransact.fulfilment.domain;

/** Where a delivery run is in dispatch (S264). */
public enum RunDispatchStatus {
    /** Confirmed in delivery-runs; its routes are not planned yet (or planning needs attention, see the error). */
    PENDING,
    /** Routes planned: one per capacity chunk, seller pickups before drops. */
    PLANNED,
    /** The run was cancelled; routes that had not started were cancelled with it. */
    CANCELLED
}
