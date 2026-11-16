package com.afrotransact.orders.domain;

/** How a shipment reaches the customer. */
public enum DeliveryMethod {
    /**
     * LEGACY: USPS / UPS / FedEx / DHL label bought by the seller through carrier-gateway; transit steps come from carrier
     * webhooks. Checkout never produces it any more (a mode of its own does not exist); parcels already in flight finish.
     */
    CARRIER,
    /**
     * AfroTransact collects the package (from the seller's store or its own warehouse) and delivers it. Staff with
     * delivery:manage run it in the staff console Delivery module (the driver app is dormant, see
     * docs/engineering/delivery-fulfilment.md); the driver app's courier events still apply when it is re-enabled.
     */
    AFROTRANSACT_DELIVERY,
    /**
     * LEGACY, read-only history: the seller shipped it themselves. Seller self-shipping is not supported any more:
     * checkout never produces this method, no seller can move such a shipment, and the value stays only so old rows
     * still load (docs/engineering/delivery-fulfilment.md "Self-shipping retired").
     */
    SELLER_SHIPS,
    /** Collected by the customer at a pickup point, locker or the seller's shop. */
    PICKUP
}
