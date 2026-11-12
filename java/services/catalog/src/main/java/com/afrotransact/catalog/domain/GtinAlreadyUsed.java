package com.afrotransact.catalog.domain;

/**
 * A product page already uses this barcode (HTTP 409 {@code catalog.gtin-exists}). The seller adds an offer to
 * {@code existingAtin} instead of creating a second page.
 */
public class GtinAlreadyUsed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String gtin14;
    private final String existingAtin;

    public GtinAlreadyUsed(String gtin14, String existingAtin) {
        super((gtin14 == null ? "A barcode on this product" : "GTIN " + gtin14) + " already has a product page"
                + (existingAtin == null ? "" : " (" + existingAtin + ")")
                + ". Add an offer to it instead of creating a duplicate.");
        this.gtin14 = gtin14;
        this.existingAtin = existingAtin;
    }

    /** The barcode as the 14-digit match key; null when the database refused a race and did not say which one. */
    public String gtin14() {
        return gtin14;
    }

    /** The ATIN of the page that has it; null only when the database refused a race and the page cannot be named. */
    public String existingAtin() {
        return existingAtin;
    }
}
