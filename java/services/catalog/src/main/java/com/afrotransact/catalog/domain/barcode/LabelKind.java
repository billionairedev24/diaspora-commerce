package com.afrotransact.catalog.domain.barcode;

/**
 * Label types from the design ({@code AfroTransact Catalog.dc.html}, identifiers and flows), with their default
 * stock size in millimetres.
 */
public enum LabelKind {
    /** AT-FN unit label (Code 128, {@code ATF…}) or the manufacturer GTIN on a unit. 2 × 1 in. */
    UNIT(50.8, 25.4),
    /** Retail unit with its manufacturer barcode (EAN-13 / UPC-A). 2 × 1.25 in. */
    RETAIL(50.8, 31.75),
    /** Shelf / bin edge label: Code 128 of an ATIN, SKU or bin with a caption. 3 × 1 in. */
    SHELF(76.2, 25.4),
    /** Case (trade unit) label: GS1-128 with (01) GTIN and (10) lot, (11) production, (15)/(17) dates, (21). 4 × 3 in. */
    CASE(101.6, 76.2),
    /** Pallet (logistic unit) label: GS1-128 with (00) SSCC, optional (02) content + (37) count, lot and dates. 4 × 6 in. */
    PALLET(101.6, 152.4),
    /** Any symbology and data on a 2 × 1 in label. */
    CUSTOM(50.8, 25.4);

    private final double widthMm;
    private final double heightMm;

    LabelKind(double widthMm, double heightMm) {
        this.widthMm = widthMm;
        this.heightMm = heightMm;
    }

    public double widthMm() {
        return widthMm;
    }

    public double heightMm() {
        return heightMm;
    }
}
