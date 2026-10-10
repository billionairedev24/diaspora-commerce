package com.afrotransact.catalog.domain;

import java.util.Map;

/** Seller input for one variant row before identifiers are issued. */
public record VariantDraft(Map<String, String> options, String sellerSku, String gtin, boolean gtinExempt,
                           long priceMinor, int quantity) {

    public VariantDraft {
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
