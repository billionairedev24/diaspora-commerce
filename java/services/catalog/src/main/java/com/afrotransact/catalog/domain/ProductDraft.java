package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Map;

/** Everything the "Add a product" flow submits, validated by {@link ProductDraftPolicy}. */
public record ProductDraft(ProductType productType, String title, Map<String, String> attributes,
                           List<ProductImage> images, String variationTheme, List<VariantDraft> variants) {

    public ProductDraft {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        images = images == null ? List.of() : List.copyOf(images);
        variants = variants == null ? List.of() : List.copyOf(variants);
    }
}
