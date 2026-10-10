package com.afrotransact.orders.domain;

/** What the customer chose after a pre-order missed its delivery date (S272). */
public enum MissedDeliveryChoice {
    /** Cancel the shipment and refund it in full (delivery fee included). */
    REFUND,
    /** Keep the order; delivery-runs schedules a new date. */
    WAIT
}
