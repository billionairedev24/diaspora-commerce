package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Map;

/**
 * Seller input for one variant row before identifiers are issued.
 *
 * @param compareAtMinor "was" price shown struck through; must be above {@code priceMinor}; null for none
 * @param images         images of this variant only (e.g. the colour); at most {@link #MAX_IMAGES}
 */
public record VariantDraft(Map<String, String> options, String sellerSku, String gtin, boolean gtinExempt,
                           long priceMinor, int quantity, Long compareAtMinor, List<ProductImage> images) {

    public static final int MAX_IMAGES = 5;

    public VariantDraft {
        options = options == null ? Map.of() : Map.copyOf(options);
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** A variant without compare-at price or images. */
    public VariantDraft(Map<String, String> options, String sellerSku, String gtin, boolean gtinExempt,
                        long priceMinor, int quantity) {
        this(options, sellerSku, gtin, gtinExempt, priceMinor, quantity, null, List.of());
    }
}
