package com.afrotransact.deliveryruns.domain;

/** Storefront placements that show an open pre-order (each can be switched off platform-wide). */
public enum Placement {
    /** Header ribbon under the header on every page (ribbon text). */
    RIBBON,
    /** Home hero and pre-order page banner (hero creative). */
    HERO,
    /** Pre-order badge on product cards and search results. */
    BADGE,
    /** Product page banner below the delivery box (PDP creative). */
    PDP,
    /** Cart header grouping pre-order items with the delivery date. */
    CART,
    /** "Send to family": metro shoppers with a saved address in a pre-order city. */
    FAMILY
}
