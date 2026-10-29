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
    NUMBER
}
