package com.afrotransact.orders.domain;

/** What a PREORDER shipment was sold through. */
public enum PreorderSource {
    /** A pre-order window (S272): staff open it for a period, everything ordered in it is delivered on its day. */
    WINDOW,
    /**
     * A delivery run staff scheduled (S260/S261): the order holds a slot on the run until its cutoff, the run goes
     * ahead when its minimum is met, and AfroTransact's own stock is delivered on its day.
     */
    RUN
}
