package com.afrotransact.deliveryruns.domain;

/** How one order of a run reaches its customer; each kind can be charged a different delivery fee. */
public enum DeliveryKind {
    /** Dropped at the customer's door. */
    DOOR,
    /** Collected by the customer at the run's pickup point. */
    PICKUP,
    /** Handed to a carrier for the last leg. */
    CARRIER
}
