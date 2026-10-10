package com.afrotransact.taxreturns.domain.returns;

/** How the item comes back. */
public enum ReturnMethod {
    /** Drop off at a carrier location with a return label (QR code) bought through carrier-gateway. */
    DROP_OFF,
    /** An AfroTransact driver collects it (only where the order was delivered by AfroTransact Delivery). */
    DRIVER_PICKUP,
    /** Refund without return: low-value items the customer keeps, or perishables reported as a problem. */
    NO_RETURN
}
