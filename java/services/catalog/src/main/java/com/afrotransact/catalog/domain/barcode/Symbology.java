package com.afrotransact.catalog.domain.barcode;

/** Barcode symbologies catalog renders. */
public enum Symbology {
    /** Code 128 (code sets B/C): ATINs, AT-FN unit labels, seller SKUs, shelf labels. */
    CODE_128,
    /** GS1-128: Code 128 starting with FNC1, carrying GS1 application identifiers (case and pallet labels). */
    GS1_128,
    /** EAN-13 / UPC-A: manufacturer GTIN on retail units. */
    EAN_13
}
