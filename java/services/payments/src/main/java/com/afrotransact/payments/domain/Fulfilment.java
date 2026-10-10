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
    AFROTRANSACT_DELIVERY;

    /** The line's shipping is AfroTransact's revenue for a marketplace seller (FBA or AfroTransact Delivery). */
    public boolean platformShips() {
        return this != SELF;
    }
}
