package com.afrotransact.offers.domain;

/**
 * Who ships the order and where the sellable quantity comes from.
 * {@link #AFROTRANSACT}: "Fulfilled by AfroTransact" — quantity is the inventory-service availability of the
 * offer's inventory SKU owned by the seller org (FBA stock, or AfroTransact Retail's own stock).
 * {@link #SELLER}: "Ships from seller" — quantity is declared by the seller on the offer.
 */
public enum Fulfilment {
    AFROTRANSACT,
    SELLER
}
