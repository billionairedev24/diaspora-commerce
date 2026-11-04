package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.VariantDraft;

/**
 * Input for creating a product. Exactly one of {@code categoryId} or {@code categoryRequestId} is set.
 * There is deliberately no "sold by" field: the owner comes from the authenticated actor.
 *
 * @param variationTheme   empty for a product without variations (exactly one variant without options)
 * @param preorderEligible null = the product type's default (true for food)
 * @param shipsNow         null = false
 * @param details          description, condition, keywords, fulfilment, package, handling time, ship-from
 * @param draftId          the saved draft this submission comes from; deleted in the same transaction
 */
public record CreateProductCommand(UUID categoryId, UUID categoryRequestId, String title,
                                   Map<String, String> attributes, List<ProductImage> images,
                                   String variationTheme, List<VariantDraft> variants, String currency,
                                   Boolean preorderEligible, Boolean shipsNow, ListingDetails details,
                                   UUID draftId) {

    public CreateProductCommand {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        images = images == null ? List.of() : List.copyOf(images);
        variants = variants == null ? List.of() : List.copyOf(variants);
        variationTheme = variationTheme == null ? "" : variationTheme.trim();
        details = details == null ? ListingDetails.DEFAULT : details;
    }

    /** A product with the default listing details, not from a draft. */
    public CreateProductCommand(UUID categoryId, UUID categoryRequestId, String title,
                                Map<String, String> attributes, List<ProductImage> images, String variationTheme,
                                List<VariantDraft> variants, String currency, Boolean preorderEligible,
                                Boolean shipsNow) {
        this(categoryId, categoryRequestId, title, attributes, images, variationTheme, variants, currency,
                preorderEligible, shipsNow, ListingDetails.DEFAULT, null);
    }

    /** A product with the default pre-order flags. */
    public CreateProductCommand(UUID categoryId, UUID categoryRequestId, String title,
                                Map<String, String> attributes, List<ProductImage> images, String variationTheme,
                                List<VariantDraft> variants, String currency) {
        this(categoryId, categoryRequestId, title, attributes, images, variationTheme, variants, currency, null,
                null);
    }
}
