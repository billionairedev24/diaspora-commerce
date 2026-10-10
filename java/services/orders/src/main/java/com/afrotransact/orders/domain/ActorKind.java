package com.afrotransact.orders.domain;

/** Who asks for a shipment transition. The application layer maps authenticated principals to these. */
public enum ActorKind {
    /** The shopper who placed the order (cancel until packed). */
    CUSTOMER,
    /** A member of the selling organisation with orders:update-status. */
    SELLER,
    /** AfroTransact staff (orders:manage / orders:update-status). */
    PLATFORM_ADMIN,
    /** Fulfilment-centre operations (fulfilment:operate, or the fulfilment service). */
    FULFILMENT_OPS,
    /** Carrier tracking webhooks relayed by carrier-gateway. */
    CARRIER,
    /** AfroTransact Delivery driver (driver app, delivery:operate). */
    COURIER,
    /**
     * A pickup point (platform counter, locker or seller pickup) reporting through carrier-gateway's
     * {@code carrier.pickup.ready} that a pickup order is ready for collection (S219).
     */
    PICKUP_POINT,
    /** The checkout saga itself. */
    SYSTEM
}
