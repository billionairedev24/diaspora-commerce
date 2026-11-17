package com.afrotransact.orders.domain;

/**
 * Who moves a shipment to the customer, derived from what the shipment carries (never stored, never guessed):
 * the fulfilment type, the delivery method and the seller-store snapshot. It decides which queue a shipment is in:
 * only {@link #AFROTRANSACT_DELIVERY} and {@link #FULFILLED_BY_AFROTRANSACT} shipments ever enter the staff
 * Delivery queue. The product has two modes: Seller pickup ({@link #PICKUP}) and Fulfilled by AfroTransact
 * ({@link #AFROTRANSACT_DELIVERY} from the seller's location, {@link #FULFILLED_BY_AFROTRANSACT} from stock AfroTransact
 * holds); seller self-shipping is not supported.
 */
public enum FulfilmentMode {
    /**
     * History only. Shipments the seller shipped themselves (or sent by a carrier label) before self-shipping was
     * retired. Never derived for a new order, never offered to a seller, never in a staff queue; only staff with
     * orders:override-shipment can still move an open one, forward only with a reason.
     */
    LEGACY_SELLER_SHIPPED,
    /**
     * The seller ships it themselves and keeps the shipping charge (behind the staff switch, off by default). The seller moves
     * it (confirm, pack, ready, shipped, delivered); no AfroTransact queue, no AfroTransact claim in any copy.
     */
    SELLER_SHIPS,
    /** Fulfilled by AfroTransact from the seller's store: staff collect from the seller's location and deliver. */
    AFROTRANSACT_DELIVERY,
    /** Fulfilled by AfroTransact (FBA stock or Retail): staff deliver from the fulfilment centre. */
    FULFILLED_BY_AFROTRANSACT,
    /** The customer collects it (the seller's store, an AfroTransact counter or a locker). */
    PICKUP,
    /** A community pre-order delivered by its window's run (delivery-runs), not by the Delivery module. */
    PREORDER_RUN;

    /** A shipment from before self-shipping was retired (read-only history). */
    public boolean legacy() {
        return this == LEGACY_SELLER_SHIPPED;
    }

    /** Enters the staff Delivery queue. */
    public boolean staffDelivers() {
        return this == AFROTRANSACT_DELIVERY || this == FULFILLED_BY_AFROTRANSACT;
    }

    /** Only these modes may show the customer or seller delivery proof or say AfroTransact delivers. */
    public boolean deliveredByAfroTransact() {
        return staffDelivers() || this == PREORDER_RUN;
    }
}
