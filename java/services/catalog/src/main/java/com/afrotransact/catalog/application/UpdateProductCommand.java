package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.VariantDraft;

/**
 * Full replacement of a product's editable content (the one-step form in edit mode, or a bulk update row applied to
 * the current product). Variants are matched to the stored ones by seller SKU: unknown SKUs are added (new ATINs)
 * and missing ones removed — removal is refused once the listing was published.
 *
 * @param categoryId null keeps the category; a published listing cannot change category
 * @param version    the version the edit was based on (optimistic locking); null skips the check
 * @param draftId    the saved draft of this edit; deleted in the same transaction
 */
public record UpdateProductCommand(UUID categoryId, String title, Map<String, String> attributes,
                                   List<ProductImage> images, String variationTheme, List<VariantDraft> variants,
                                   Boolean preorderEligible, Boolean shipsNow, ListingDetails details, Long version,
                                   UUID draftId) {

    public UpdateProductCommand {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        images = images == null ? List.of() : List.copyOf(images);
        variants = variants == null ? List.of() : List.copyOf(variants);
        variationTheme = variationTheme == null ? "" : variationTheme.trim();
        details = details == null ? ListingDetails.DEFAULT : details;
    }
}
