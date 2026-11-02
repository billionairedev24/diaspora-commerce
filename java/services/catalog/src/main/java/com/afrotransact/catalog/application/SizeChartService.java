package com.afrotransact.catalog.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.sizing.ChartContent;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizeChart;
import com.afrotransact.catalog.domain.sizing.SuggestedMeasurement;

/**
 * Sellers' size charts. The seller organisation writes, edits, duplicates and retires its own charts (members with
 * catalog:write and the organisation's product relationship; AfroTransact Retail staff for the platform's own
 * charts); other staff and service clients can read any organisation's charts but never change them. Charts of
 * another organisation do not exist for a seller (404).
 */
public interface SizeChartService {

    /**
     * Charts, newest change first. Sellers see their own organisation's; staff pass {@code ownerOrgId} (omitted =
     * every organisation, which needs the platform-wide view).
     *
     * @param productTypeCode only charts made for this product type or for any type; null = all
     * @param categoryId      only charts made for this category node or for any; null = all
     */
    PageView<ChartView> list(String ownerOrgId, String productTypeCode, UUID categoryId, boolean includeRetired,
                             String text, int page, int size);

    ChartView get(UUID id);

    /** Validates and stores a new chart; its name must differ (ignoring case) from the owner's other active charts. */
    ChartView create(ChartContent content);

    /**
     * Replaces the chart's content. Every size still sold by a product that uses the chart must keep its row.
     *
     * @param expectedVersion the version the editor loaded (409 when stale); null skips the check
     */
    ChartView update(UUID id, Long expectedVersion, ChartContent content);

    /**
     * Retires the chart: products that use it keep showing it, but it can no longer be linked to products and its name
     * is free for a new chart. Retiring twice is harmless.
     */
    ChartView retire(UUID id);

    /** A copy under {@code newName} (default: "<name> (copy)"), for a similar product. */
    ChartView duplicate(UUID id, String newName);

    /**
     * What a product type suggests for the chart editor: the sizes and measurement columns of its sizing trait. A type
     * that is not sized answers {@code sized = false} with empty lists.
     *
     * @param categoryId  a product-type category node; or
     * @param productType the product type code; one of the two is required
     */
    Suggestions suggestions(UUID categoryId, String productType);

    /** A chart with how many products use it. */
    record ChartView(SizeChart chart, long productCount) {
    }

    /**
     * @param sizes                 the category's suggested size labels, in order
     * @param sizesAllowCustomValues the seller may add size labels of their own (always true for a sized type)
     */
    record Suggestions(String productTypeCode, String productTypeLabel, boolean sized, boolean sizeChartRequired,
                       String sizeDimension, List<String> sizes, boolean sizesAllowCustomValues,
                       MeasurementUnit unitSystem, List<SuggestedMeasurement> measurements) {

        public Suggestions {
            sizes = List.copyOf(sizes);
            measurements = List.copyOf(measurements);
        }
    }
}
