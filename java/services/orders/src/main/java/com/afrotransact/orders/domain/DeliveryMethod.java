package com.afrotransact.orders.domain;

/** How a shipment reaches the customer. */
public enum DeliveryMethod {
    /** USPS / UPS / FedEx / DHL through carrier-gateway; transit steps come from carrier webhooks. */
    CARRIER,
    /** Our own drivers; transit steps come from the driver app (courier events). */
    AFROTRANSACT_DELIVERY,
    /** Collected by the customer at a pickup point, locker or the seller's shop. */
    PICKUP
}
