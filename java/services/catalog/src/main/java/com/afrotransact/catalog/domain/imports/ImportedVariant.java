package com.afrotransact.catalog.domain.imports;

import java.util.Map;

/**
 * One variant row as read. In Update mode null fields are left unchanged.
 *
 * @param priceMinor     null when blank (Update mode)
 * @param compareAtMinor null when blank or not above the price
 * @param quantity       null when blank (Update mode)
 */
public record ImportedVariant(int rowNumber, String sku, Map<String, String> options, String gtin,
                              boolean gtinExempt, Long priceMinor, Long compareAtMinor, Integer quantity) {

    public ImportedVariant {
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
