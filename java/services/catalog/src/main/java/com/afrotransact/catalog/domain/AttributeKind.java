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
    BRAND
}
