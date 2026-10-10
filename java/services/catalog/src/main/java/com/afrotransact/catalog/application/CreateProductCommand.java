package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.VariantDraft;

/**
 * Input for creating a product. Exactly one of {@code categoryId} or {@code categoryRequestId} is set.
 * There is deliberately no "sold by" field: the owner comes from the authenticated actor.
 *
 * @param preorderEligible null = the product type's default (true for food)
 * @param shipsNow         null = false
 */
public record CreateProductCommand(UUID categoryId, UUID categoryRequestId, String title,
                                   Map<String, String> attributes, List<ProductImage> images,
                                   String variationTheme, List<VariantDraft> variants, String currency,
                                   Boolean preorderEligible, Boolean shipsNow) {

    /** A product with the default pre-order flags. */
    public CreateProductCommand(UUID categoryId, UUID categoryRequestId, String title,
                                Map<String, String> attributes, List<ProductImage> images, String variationTheme,
                                List<VariantDraft> variants, String currency) {
        this(categoryId, categoryRequestId, title, attributes, images, variationTheme, variants, currency, null,
                null);
    }
}
