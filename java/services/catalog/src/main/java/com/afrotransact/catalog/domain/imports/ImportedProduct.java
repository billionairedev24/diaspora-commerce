package com.afrotransact.catalog.domain.imports;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.PackageDimensions;
import com.afrotransact.catalog.domain.sizing.FitNote;

/**
 * One product of an upload: the rows sharing a Parent SKU (Create mode) or the rows of one existing listing
 * (Update mode). Product-level values come from the first row that has them; in Update mode null means unchanged.
 *
 * @param groupKey  Parent SKU (or the SKU of a product without variations); the product id in Update mode
 * @param productId Update mode: the listing to change; null when creating
 * @param sizeChartId the seller's size chart named in the Size chart column, resolved by name; null for none
 * @param fitNote   how the sizes fit; null for none (unchanged in Update mode)
 * @param dangerousGoods the dangerous goods answer; null when blank or not asked
 * @param labelPhotoUrl  the label photo link or library file name; null when none
 * @param exemptions     rule field id to the reason the seller gave for not providing it
 */
public record ImportedProduct(String groupKey, UUID productId, ImportCategory category, String productTypeCode,
                              String title, String description, Map<String, String> attributes,
                              List<String> imageUrls, List<String> keywords, ItemCondition condition,
                              FulfilmentMode fulfilment, Integer handlingDays, PackageDimensions packaging,
                              Boolean preorderEligible, String variationTheme, List<ImportedVariant> variants,
                              UUID sizeChartId, String sizeChartName, FitNote fitNote,
                              Map<String, Object> sellerBehaviour, Boolean dangerousGoods, String labelPhotoUrl,
                              Map<String, String> exemptions) {

    /** A product without the answers of the listing rules. */
    public ImportedProduct(String groupKey, UUID productId, ImportCategory category, String productTypeCode,
                           String title, String description, Map<String, String> attributes, List<String> imageUrls,
                           List<String> keywords, ItemCondition condition, FulfilmentMode fulfilment,
                           Integer handlingDays, PackageDimensions packaging, Boolean preorderEligible,
                           String variationTheme, List<ImportedVariant> variants, UUID sizeChartId,
                           String sizeChartName, FitNote fitNote, Map<String, Object> sellerBehaviour) {
        this(groupKey, productId, category, productTypeCode, title, description, attributes, imageUrls, keywords,
                condition, fulfilment, handlingDays, packaging, preorderEligible, variationTheme, variants, sizeChartId,
                sizeChartName, fitNote, sellerBehaviour, null, null, null);
    }

    /** A product without seller behaviour answers. */
    public ImportedProduct(String groupKey, UUID productId, ImportCategory category, String productTypeCode,
                           String title, String description, Map<String, String> attributes, List<String> imageUrls,
                           List<String> keywords, ItemCondition condition, FulfilmentMode fulfilment,
                           Integer handlingDays, PackageDimensions packaging, Boolean preorderEligible,
                           String variationTheme, List<ImportedVariant> variants, UUID sizeChartId,
                           String sizeChartName, FitNote fitNote) {
        this(groupKey, productId, category, productTypeCode, title, description, attributes, imageUrls, keywords,
                condition, fulfilment, handlingDays, packaging, preorderEligible, variationTheme, variants, sizeChartId,
                sizeChartName, fitNote, null);
    }

    /** A product without a size chart. */
    public ImportedProduct(String groupKey, UUID productId, ImportCategory category, String productTypeCode,
                           String title, String description, Map<String, String> attributes, List<String> imageUrls,
                           List<String> keywords, ItemCondition condition, FulfilmentMode fulfilment,
                           Integer handlingDays, PackageDimensions packaging, Boolean preorderEligible,
                           String variationTheme, List<ImportedVariant> variants) {
        this(groupKey, productId, category, productTypeCode, title, description, attributes, imageUrls, keywords,
                condition, fulfilment, handlingDays, packaging, preorderEligible, variationTheme, variants, null, null,
                null);
    }

    public ImportedProduct {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        keywords = keywords == null ? null : List.copyOf(keywords);
        variants = List.copyOf(variants);
        sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        exemptions = exemptions == null ? Map.of() : Map.copyOf(exemptions);
        labelPhotoUrl = labelPhotoUrl == null || labelPhotoUrl.isBlank() ? null : labelPhotoUrl.strip();
    }

    /** The same product with the answers of the listing rules. */
    public ImportedProduct withRuleAnswers(Boolean dangerous, String labelPhoto, Map<String, String> reasons) {
        return new ImportedProduct(groupKey, productId, category, productTypeCode, title, description, attributes,
                imageUrls, keywords, condition, fulfilment, handlingDays, packaging, preorderEligible, variationTheme,
                variants, sizeChartId, sizeChartName, fitNote, sellerBehaviour, dangerous, labelPhoto, reasons);
    }

    public List<Integer> rowNumbers() {
        return variants.stream().map(ImportedVariant::rowNumber).toList();
    }
}
