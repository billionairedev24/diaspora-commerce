package com.afrotransact.catalog.domain;

/** Who lists the product. Never chosen by the user: derived from the signed-in actor. */
public enum ListingOwnerKind {
    /** Independent seller organisation. */
    SELLER,
    /** AfroTransact Retail (first-party, stock from platform inventory). */
    PLATFORM
}
