package com.afrotransact.fulfilment.domain;

/** REQUESTED (waiting for a route) → ROUTED → COLLECTED | FAILED; CANCELLED before collection. */
public enum ReturnPickupStatus {
    REQUESTED,
    ROUTED,
    COLLECTED,
    FAILED,
    CANCELLED;

    public boolean open() {
        return this == REQUESTED || this == ROUTED;
    }
}
