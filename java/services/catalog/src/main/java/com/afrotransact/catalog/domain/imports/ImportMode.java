package com.afrotransact.catalog.domain.imports;

/** What a bulk upload does with its rows. */
public enum ImportMode {
    /** Every row is a new product (or a variant of a new product); SKUs already listed are errors. */
    CREATE,
    /** Every row updates the existing variant with that SKU; blank cells leave a value unchanged. */
    UPDATE,
    /**
     * Every row sells an existing product page (S393): the row names it by barcode, ATIN or brand and part number and
     * carries offer terms only (price, stock, condition, shipping, handling). No title, attribute or image columns.
     */
    OFFER,
    /** A lightweight price and stock feed by seller SKU or ATIN (S394): up to 50,000 rows; blank cells change nothing. */
    PRICE_QTY;

    /** Modes whose rows are offers, not product pages: the rows are applied through offers-service. */
    public boolean offerRows() {
        return this == OFFER || this == PRICE_QTY;
    }
}
