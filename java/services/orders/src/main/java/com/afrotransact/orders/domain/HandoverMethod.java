package com.afrotransact.orders.domain;

/** How the driver completed a delivery. */
public enum HandoverMethod {
    /** Handed to the customer: needs the handover code when one is required. */
    CUSTOMER,
    /** Handed to someone else: name, relationship and signature. */
    SOMEONE_ELSE,
    /** Left in a safe place: never for perishables or hand-delivery orders. */
    SAFE_PLACE
}
