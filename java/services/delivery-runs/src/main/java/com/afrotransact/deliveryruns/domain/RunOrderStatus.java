package com.afrotransact.deliveryruns.domain;

/** A run slot held by an order. */
public enum RunOrderStatus {
    /** Slot held while the run takes orders; payment is only authorised. */
    RESERVED,
    /** The run was confirmed at cutoff: capture the payment, plan the sweep. */
    CONFIRMED,
    /** The order gave its slot back (customer cancelled before cutoff, or moved to another run). */
    RELEASED,
    /** The run was cancelled: release stock and the payment hold, tell the customer. */
    CANCELLED;

    /** Holds capacity. */
    public boolean active() {
        return this == RESERVED || this == CONFIRMED;
    }
}
