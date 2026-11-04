package com.afrotransact.catalog.domain.sizing;

import java.util.List;
import java.util.UUID;

/**
 * What a seller enters for a size chart (create, edit, duplicate, bulk upload); validated and normalised by
 * {@link SizeChartRules}.
 */
public record ChartContent(String name, MeasurementUnit unitSystem, String productTypeCode, UUID categoryId,
                           List<ChartColumn> columns, List<ChartRow> rows, FitNote fitNote, String howToMeasure) {

    public ChartContent {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
