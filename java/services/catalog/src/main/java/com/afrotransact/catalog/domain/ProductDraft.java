package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Map;

/** Everything the one-step product form (or one bulk-upload product) submits, validated by {@link ProductDraftPolicy}. */
public record ProductDraft(ProductType productType, String title, Map<String, String> attributes,
                           List<ProductImage> images, String variationTheme, List<VariantDraft> variants,
                           ListingDetails details) {

    public ProductDraft {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        images = images == null ? List.of() : List.copyOf(images);
        variants = variants == null ? List.of() : List.copyOf(variants);
        variationTheme = variationTheme == null ? "" : variationTheme.trim();
        details = details == null ? ListingDetails.DEFAULT : details;
    }

    /** A draft with the default listing details. */
    public ProductDraft(ProductType productType, String title, Map<String, String> attributes,
                        List<ProductImage> images, String variationTheme, List<VariantDraft> variants) {
        this(productType, title, attributes, images, variationTheme, variants, ListingDetails.DEFAULT);
    }
}
