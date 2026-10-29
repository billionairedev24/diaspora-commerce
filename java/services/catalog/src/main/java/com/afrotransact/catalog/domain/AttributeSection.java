package com.afrotransact.catalog.domain;

/** Where the one-step product form shows an attribute: with the details, or in a conditional section. */
public enum AttributeSection {
    /** Product details shown for every product type. */
    DETAILS,
    /** Food and perishables: shelf life, storage, ingredients, allergens (product types with the food trait). */
    FOOD,
    /** Regulated items: permit, licence and certificate references. */
    REGULATED
}
