package com.afrotransact.catalog.domain;

/**
 * An item without a barcode has the same brand and part number as a page that already exists (HTTP 409
 * {@code catalog.page-exists}): the seller adds an offer to it instead of creating a second page.
 */
public class PageAlreadyExists extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String existingAtin;

    public PageAlreadyExists(String existingAtin) {
        super("This product (same brand and part number) already has a page (" + existingAtin
                + "). Add your offer to it instead of creating a duplicate.");
        this.existingAtin = existingAtin;
    }

    public String existingAtin() {
        return existingAtin;
    }
}
