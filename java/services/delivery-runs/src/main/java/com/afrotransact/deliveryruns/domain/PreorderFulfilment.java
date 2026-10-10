package com.afrotransact.deliveryruns.domain;

/** How a pre-order is handed over on the delivery day. */
public enum PreorderFulfilment {
    /** Delivered to the door (flat fee). */
    DOOR,
    /** Collected at the pickup point (free by default). */
    PICKUP
}
