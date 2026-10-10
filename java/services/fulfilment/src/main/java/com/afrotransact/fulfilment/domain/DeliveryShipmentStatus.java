package com.afrotransact.fulfilment.domain;

/** Where an AfroTransact Delivery shipment is in dispatch. */
public enum DeliveryShipmentStatus {
    AWAITING_ROUTE,
    ROUTED,
    DELIVERED,
    FAILED,
    CANCELLED
}
