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

    /** The seller declares the quantity and keeps the stock (SELLER and AFROTRANSACT_DELIVERY). */
    public boolean sellerStock() {
        return this != AFROTRANSACT;
    }
}
