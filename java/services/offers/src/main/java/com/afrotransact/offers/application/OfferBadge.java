package com.afrotransact.offers.application;

/** Badges shown next to an offer in the buy box and the "Other sellers on AfroTransact" list. */
public enum OfferBadge {
    BUY_BOX_WINNER,
    LOWEST_PRICE,
    FASTEST_DELIVERY,
    FULFILLED_BY_AFROTRANSACT,
    SHIPS_FROM_SELLER,
    /** S255: packed by the seller, delivered by an AfroTransact driver from the seller's store. */
    AFROTRANSACT_DELIVERY,
    SOLD_BY_AFROTRANSACT,
    LOW_STOCK,
    RENEWED,
    USED
}
