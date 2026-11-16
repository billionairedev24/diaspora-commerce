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
    /**
     * AfroTransact staff running a delivery in the staff console Delivery module (delivery:manage). Moves only
     * AFROTRANSACT_DELIVERY shipments, through pickup, out for delivery, delivered (with proof) and failed.
     */
    DELIVERY_STAFF,
    /** AfroTransact Delivery driver (driver app, delivery:operate). */
    COURIER,
    /**
     * A pickup point (platform counter, locker or seller pickup) reporting through carrier-gateway's
     * {@code carrier.pickup.ready} that a pickup order is ready for collection (S219).
     */
    PICKUP_POINT,
    /**
     * AfroTransact staff holding {@code orders:override-shipment} correcting a shipment Fulfilled by AfroTransact or an
     * old seller-shipped one (legacy history). Forward only, a reason is always required and the change is audited with who and why.
     */
    ADMIN_OVERRIDE,
    /** The checkout saga itself. */
    SYSTEM
}
