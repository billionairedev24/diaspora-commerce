package com.afrotransact.catalog.domain;

/** How a product-type attribute is entered and validated. */
public enum AttributeKind {
    /** Free text on one line. */
    TEXT,
    /** Long text (ingredients, INCI). */
    MULTILINE,
    /** One of the attribute's {@code options}. */
    ENUM,
    /** A number, optionally followed by one of the attribute's {@code units} ("12 months"). */
    NUMBER,
    /**
     * The product's brand, chosen from the brand registry (or typed in as a new one) for the product's category. Its
     * {@code options} are the no-brand choices the type allows ({@code UNBRANDED}, {@code HANDMADE}); none means a
     * brand is always needed when it is required.
     */
    BRAND,
    /** Yes or no (tax fact). */
    BOOLEAN,
    /** A decimal number in the attribute's {@code unit} (tax fact: net weight in oz). */
    DECIMAL,
    /** A whole number (tax fact: servings per package). */
    INTEGER,
    /** A percentage from 0 to 100 (tax fact: juice content). */
    PERCENT,
    /** An evidence file (label photo, Drug Facts); the value is the stored evidence reference. */
    FILE
}
