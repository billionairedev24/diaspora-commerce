package com.afrotransact.offers.domain;

/**
 * Who ships the order and where the sellable quantity comes from.
 * {@link #AFROTRANSACT}: "Fulfilled by AfroTransact" — quantity is the inventory-service availability of the
 * offer's inventory SKU owned by the seller org (FBA stock, or AfroTransact Retail's own stock).
 * {@link #SELLER}: "Ships from seller" — quantity is declared by the seller on the offer.
 * {@link #AFROTRANSACT_DELIVERY}: "AfroTransact Delivery from the seller's store" (S255) — the seller packs, the
 * nearest AfroTransact driver collects at the offer's ship-from location (AfroTransact pickup enabled) and delivers;
 * quantity is declared by the seller.
 */
public enum Fulfilment {
    AFROTRANSACT,
    SELLER,
    AFROTRANSACT_DELIVERY;

    /**
     * True for Fulfilled by AfroTransact. A seller may hold one open offer per variant and condition in each channel
     * (Fulfilled by AfroTransact, and seller-fulfilled = SELLER or AFROTRANSACT_DELIVERY), so the same product can be
     * sold from the seller's own store and from an AfroTransact fulfilment centre at the same time (S257).
     */
    public boolean fulfilledByAfroTransact() {
        return this == AFROTRANSACT;
    }

    /** The seller declares the quantity and keeps the stock (SELLER and AFROTRANSACT_DELIVERY). */
    public boolean sellerStock() {
        return this != AFROTRANSACT;
    }
}
