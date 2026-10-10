package com.afrotransact.payments.domain;

/** How an AfroTransact Delivery tariff prices a trip. */
public enum DeliveryMode {
    /** A fixed fee per package, whatever the distance (within the service radius). */
    FLAT,
    /** Base fee + per-mile rate (or a distance band's fee), clamped to a minimum and a maximum. */
    DISTANCE
}
