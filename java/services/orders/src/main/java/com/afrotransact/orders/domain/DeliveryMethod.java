package com.afrotransact.orders.domain;

/** How a shipment reaches the customer. */
public enum DeliveryMethod {
    /** USPS / UPS / FedEx / DHL through carrier-gateway; transit steps come from carrier webhooks. */
    CARRIER,
    /**
     * AfroTransact collects the package (from the seller's store or its own warehouse) and delivers it. Staff with
     * delivery:manage run it in the staff console Delivery module (the driver app is dormant, see
     * docs/engineering/delivery-fulfilment.md); the driver app's courier events still apply when it is re-enabled.
     */
    AFROTRANSACT_DELIVERY,
    /**
     * The seller ships it themselves (the seller did not opt into delivery by AfroTransact and it is not fulfilled by
     * AfroTransact). The seller marks it shipped with tracking or handover details and delivered; no AfroTransact
     * driver or staff queue is ever involved. The customer pays the same flat delivery fee as for any delivery.
     */
    SELLER_SHIPS,
    /** Collected by the customer at a pickup point, locker or the seller's shop. */
    PICKUP
}
