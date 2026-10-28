package com.afrotransact.catalog.domain.imports;

/** The kind of value an upload column holds (drives template validation and parsing). */
public enum ColumnKind {
    TEXT,
    LONG_TEXT,
    /** One of the column's options (a dropdown in the Excel template). */
    ENUM,
    /** Yes or No. */
    BOOLEAN,
    /** A whole number. */
    INTEGER,
    /** A decimal number. */
    DECIMAL,
    /** US dollars, up to two decimals. */
    MONEY,
    /** Barcode digits. */
    GTIN,
    /** Image URLs separated by spaces, new lines or "|". */
    URLS
}
