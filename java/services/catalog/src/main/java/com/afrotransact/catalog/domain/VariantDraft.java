package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Map;

/**
 * Seller input for one variant row before identifiers are issued.
 *
 * @param compareAtMinor "was" price shown struck through; must be above {@code priceMinor}; null for none
 * @param images         images of this variant only (e.g. the colour); at most {@link #MAX_IMAGES}
 * @param atin           the existing variant's ATIN (bulk update matches by SKU, else GTIN, else ATIN); null for new
 * @param taxFacts       the product facts of this variant (owner VARIANT) by fact id, typed
 */
public record VariantDraft(Map<String, String> options, String sellerSku, String gtin, boolean gtinExempt,
                           long priceMinor, int quantity, Long compareAtMinor, List<ProductImage> images,
                           String atin, Map<String, Object> taxFacts) {

    public static final int MAX_IMAGES = 5;

    public VariantDraft {
        options = options == null ? Map.of() : Map.copyOf(options);
        images = images == null ? List.of() : List.copyOf(images);
        taxFacts = taxFacts == null ? Map.of() : Map.copyOf(taxFacts);
    }

    /** A variant without ATIN or tax facts. */
    public VariantDraft(Map<String, String> options, String sellerSku, String gtin, boolean gtinExempt,
                        long priceMinor, int quantity, Long compareAtMinor, List<ProductImage> images) {
        this(options, sellerSku, gtin, gtinExempt, priceMinor, quantity, compareAtMinor, images, null, null);
    }

    /** The same row with other tax facts. */
    public VariantDraft withTaxFacts(Map<String, Object> facts) {
        return new VariantDraft(options, sellerSku, gtin, gtinExempt, priceMinor, quantity, compareAtMinor, images,
                atin, facts);
    }

    /** The same row with another SKU. */
    public VariantDraft withSellerSku(String sku) {
        return new VariantDraft(options, sku, gtin, gtinExempt, priceMinor, quantity, compareAtMinor, images, atin,
                taxFacts);
    }

    /** A variant without compare-at price or images. */
    public VariantDraft(Map<String, String> options, String sellerSku, String gtin, boolean gtinExempt,
                        long priceMinor, int quantity) {
        this(options, sellerSku, gtin, gtinExempt, priceMinor, quantity, null, List.of());
    }
}
