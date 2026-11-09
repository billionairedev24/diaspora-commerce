package com.afrotransact.catalog.domain.imports;

import java.util.UUID;

/**
 * The organisation's listing that a row names (Update mode): by SKU, else GTIN, else ATIN.
 *
 * @param sellerSku the listing's own variant SKU (the key the update writes by); null when unknown
 */
public record ExistingListing(UUID productId, String productTypeCode, String sellerSku) {

    /** Prefix of the entries keyed by barcode ({@code gtin:0123...}) in the existing-listings map. */
    public static final String GTIN_KEY = "gtin:";
    /** Prefix of the entries keyed by variant ATIN ({@code atin:AT00001234}). */
    public static final String ATIN_KEY = "atin:";

    public ExistingListing(UUID productId, String productTypeCode) {
        this(productId, productTypeCode, null);
    }
}
