package com.afrotransact.orders.domain;

/** Customer's handover preference for a delivery. */
public enum HandoverPreference {
    /** Hand it to me (needs the handover code). */
    HAND,
    /** Leave at the door. */
    DOOR,
    /** Leave with the front desk / concierge. */
    DESK
}
