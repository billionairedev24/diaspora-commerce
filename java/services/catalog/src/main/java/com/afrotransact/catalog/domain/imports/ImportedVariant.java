package com.afrotransact.catalog.domain.imports;

import java.util.Map;

import com.afrotransact.catalog.domain.PackageDimensions;

/**
 * One variant row as read. In Update mode null fields are left unchanged.
 *
 * @param priceMinor     null when blank (Update mode)
 * @param compareAtMinor null when blank or not above the price
 * @param quantity       null when blank (Update mode)
 * @param taxFacts       the product facts of this row (owner VARIANT) read from the template's fact columns
 * @param packaging      the package of this row as shipped; null when the row gives none (it starts from the product's
 *                       first row, and in Update mode keeps the stored package)
 */
public record ImportedVariant(int rowNumber, String sku, Map<String, String> options, String gtin,
                              boolean gtinExempt, Long priceMinor, Long compareAtMinor, Integer quantity,
                              Map<String, Object> taxFacts, PackageDimensions packaging) {

    /** A row without a package of its own. */
    public ImportedVariant(int rowNumber, String sku, Map<String, String> options, String gtin,
                           boolean gtinExempt, Long priceMinor, Long compareAtMinor, Integer quantity,
                           Map<String, Object> taxFacts) {
        this(rowNumber, sku, options, gtin, gtinExempt, priceMinor, compareAtMinor, quantity, taxFacts, null);
    }

    public ImportedVariant {
        options = options == null ? Map.of() : Map.copyOf(options);
        taxFacts = taxFacts == null ? Map.of() : Map.copyOf(taxFacts);
    }

    /** A row without tax facts. */
    public ImportedVariant(int rowNumber, String sku, Map<String, String> options, String gtin, boolean gtinExempt,
                           Long priceMinor, Long compareAtMinor, Integer quantity) {
        this(rowNumber, sku, options, gtin, gtinExempt, priceMinor, compareAtMinor, quantity, null);
    }
}
