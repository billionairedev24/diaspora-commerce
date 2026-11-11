package com.afrotransact.catalog.domain.rules;

/** How the product form and the upload template ask for a rule field. */
public enum FieldControl {
    TEXT,
    LONG_TEXT,
    CHOICE,
    /** Several of the options ticked (allergens). */
    MULTI_CHOICE,
    /** A number with a unit (net weight, shelf life). */
    MEASURE,
    WHOLE_NUMBER,
    YES_NO,
    BRAND,
    BARCODE,
    MONEY,
    KEYWORDS,
    IMAGES,
    /** One typed image slot (the label photo). */
    PHOTO,
    CONDITION,
    SHIP_FROM,
    /** Length, width, height and weight of the box, per variant. */
    PACKAGE,
    SIZES,
    /** A file upload reviewed at approval. */
    DOCUMENT
}
