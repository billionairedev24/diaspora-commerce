package com.afrotransact.catalog.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.ValidatedRow;
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

    /**
     * The size chart upload template: one row per size, the product type's suggested measurements as columns (the
     * generic template when neither is given).
     *
     * @param productType the product type code, or
     * @param categoryId  a product-type category node
     */
    FileContent template(String productType, UUID categoryId, SpreadsheetFormat format);

    /**
     * Reads a size chart sheet (.xlsx or .csv) and saves its charts for the organisation: a chart whose name matches
     * one of the organisation's active charts updates it (nothing changes when the content is the same), any other
     * name creates a chart, and re-uploading the file is harmless. A chart is saved whole or not at all. Unreadable
     * files are refused with a 422 and nothing is saved; row problems are reported per row.
     *
     * @param productType the product type the charts are made for (as the template's); null = any
     * @param dryRun      validate and report what would happen without saving anything
     */
    ImportReport importCharts(String fileName, byte[] content, String productType, UUID categoryId, boolean dryRun);

    /** A generated file. */
    record FileContent(String fileName, String contentType, byte[] content) {
    }

    /** What an import did (or, for a dry run, would do) to one chart. */
    enum ImportAction { CREATE, UPDATE, UNCHANGED, FAILED }

    /**
     * @param chartId the saved chart; null for a dry run's new charts and for failed ones
     * @param rows    the file rows of the chart
     * @param message why a chart failed; null otherwise
     */
    record ChartOutcome(String name, ImportAction action, UUID chartId, List<Integer> rows, String message) {

        public ChartOutcome {
            rows = List.copyOf(rows);
        }
    }

    /**
     * The result of a size chart upload.
     *
     * @param applied false for a dry run
     * @param rows    every data row with its status and messages (group key = chart name, sku = size)
     */
    record ImportReport(boolean applied, String fileName, List<String> fileWarnings, int chartCount,
                        List<ChartOutcome> charts, List<ValidatedRow> rows) {

        public ImportReport {
            fileWarnings = List.copyOf(fileWarnings);
            charts = List.copyOf(charts);
            rows = List.copyOf(rows);
        }
    }

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
