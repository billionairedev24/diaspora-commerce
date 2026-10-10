package com.afrotransact.fulfilment.domain;

/** How the driver completed a drop-off (same values as orders-service's courier proof). */
public enum HandoverMethod {
    /** Handed to the customer: their 4-digit code when the shipment needs one (checked by orders-service). */
    CUSTOMER,
    /** Handed to someone else: name, relationship and signature. */
    SOMEONE_ELSE,
    /** Left in a safe place: never for perishables or hand-delivery orders. */
    SAFE_PLACE
}
