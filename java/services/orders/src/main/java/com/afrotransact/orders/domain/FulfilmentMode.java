package com.afrotransact.orders.domain;

/**
 * Who moves a shipment to the customer, derived from what the shipment carries (never stored, never guessed):
 * the fulfilment type, the delivery method and the seller-store snapshot. It decides which queue a shipment is in:
 * only {@link #AFROTRANSACT_DELIVERY} and {@link #FULFILLED_BY_AFROTRANSACT} shipments ever enter the staff
 * Delivery queue.
 */
public enum FulfilmentMode {
    /** The seller ships it and marks it shipped and delivered. No AfroTransact driver or staff queue. */
    SELLER_SHIPS,
    /** The seller opted into delivery by AfroTransact: staff collect from the seller's store and deliver. */
    AFROTRANSACT_DELIVERY,
    /** Fulfilled by AfroTransact (FBA or Retail): staff deliver from the fulfilment centre. */
    FULFILLED_BY_AFROTRANSACT,
    /** The customer collects it (the seller's store, an AfroTransact counter or a locker). */
    PICKUP,
    /** A community pre-order delivered by its window's run (delivery-runs), not by the Delivery module. */
    PREORDER_RUN;

    /** Enters the staff Delivery queue. */
    public boolean staffDelivers() {
        return this == AFROTRANSACT_DELIVERY || this == FULFILLED_BY_AFROTRANSACT;
    }

    /** Only these modes may show the customer or seller delivery proof or say AfroTransact delivers. */
    public boolean deliveredByAfroTransact() {
        return staffDelivers() || this == PREORDER_RUN;
    }
}
