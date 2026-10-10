package com.afrotransact.payments.domain;

/**
 * Who ships a line: AfroTransact fulfilment centres (FBA, also all 1P stock), the seller itself, or AfroTransact
 * Delivery from the seller's store (S255: the seller packs, an AfroTransact driver collects and delivers).
 */
public enum Fulfilment {
    FBA,
    SELF,
    /**
     * Seller-packed, delivered by an AfroTransact driver from the seller's pickup location: the customer's delivery
     * charge is AfroTransact's (4200), the seller pays commission + the pickup-delivery fee (4250), no FBA fee.
     */
    AFROTRANSACT_DELIVERY,
    /**
     * Community pre-order (S272): the line's shipping is its share of the window's flat delivery fee (door delivery;
     * 0 for pickup), AfroTransact's local delivery revenue (4250 Local delivery fees) for every seller, AfroTransact
     * Retail included; the seller pays commission, no FBA fee.
     */
    PREORDER;

    /**
     * The line's shipping is AfroTransact's revenue for a marketplace seller (FBA, AfroTransact Delivery or a
     * pre-order delivery fee).
     */
    public boolean platformShips() {
        return this != SELF;
    }

    /** The line's shipping is a pre-order delivery fee, booked to 4250 rather than 4200 (Retail's too). */
    public boolean shippingIsLocalDeliveryFee() {
        return this == PREORDER;
    }
}
