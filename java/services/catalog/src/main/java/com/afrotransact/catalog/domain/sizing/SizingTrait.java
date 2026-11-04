package com.afrotransact.catalog.domain.sizing;

import java.util.List;

/**
 * Marks a product type as sized. Staff only switch this on per product type; sellers write the size charts and the
 * size labels themselves.
 *
 * @param sizeChartRequired     a listing cannot be submitted without a size chart, and every size it sells needs a row
 * @param sizeDimension         the variation dimension that holds the size ("Size"); its options are the suggested
 *                              sizes. Blank in stored JSON = "Size".
 * @param suggestedMeasurements columns the chart editor and the bulk template pre-fill
 */
public record SizingTrait(Boolean sizeChartRequired, String sizeDimension,
                          List<SuggestedMeasurement> suggestedMeasurements) {

    public static final String DEFAULT_DIMENSION = "Size";

    public SizingTrait {
        sizeChartRequired = sizeChartRequired != null && sizeChartRequired;
        sizeDimension = sizeDimension == null || sizeDimension.isBlank() ? DEFAULT_DIMENSION : sizeDimension.strip();
        suggestedMeasurements = suggestedMeasurements == null ? List.of() : List.copyOf(suggestedMeasurements);
    }
}
