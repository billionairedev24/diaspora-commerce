package com.afrotransact.catalog.domain.imports;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.PackageDimensions;

/**
 * One product of an upload: the rows sharing a Parent SKU (Create mode) or the rows of one existing listing
 * (Update mode). Product-level values come from the first row that has them; in Update mode null means unchanged.
 *
 * @param groupKey  Parent SKU (or the SKU of a product without variations); the product id in Update mode
 * @param productId Update mode: the listing to change; null when creating
 */
public record ImportedProduct(String groupKey, UUID productId, ImportCategory category, String productTypeCode,
                              String title, String description, Map<String, String> attributes,
                              List<String> imageUrls, List<String> keywords, ItemCondition condition,
                              FulfilmentMode fulfilment, Integer handlingDays, PackageDimensions packaging,
                              Boolean preorderEligible, String variationTheme, List<ImportedVariant> variants) {

    public ImportedProduct {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        keywords = keywords == null ? null : List.copyOf(keywords);
        variants = List.copyOf(variants);
    }

    public List<Integer> rowNumbers() {
        return variants.stream().map(ImportedVariant::rowNumber).toList();
    }
}
