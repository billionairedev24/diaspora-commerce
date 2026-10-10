package com.afrotransact.catalog.domain.barcode;

/** Output formats of the label endpoints. */
public enum LabelFormat {
    /** Vector label (single label). */
    SVG,
    /** Bare barcode symbol with quiet zones, for embedding (single label). */
    PNG,
    /** Zebra Programming Language, one {@code ^XA…^XZ} block per label. */
    ZPL,
    /** One label per page, or a sheet of labels. */
    PDF
}
