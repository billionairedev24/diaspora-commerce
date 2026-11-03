package com.afrotransact.catalog.domain.sizing;

import java.util.Map;
import java.util.Objects;

/**
 * One size of a chart.
 *
 * @param size   the size label as the seller writes it on their variants ("M", "UK 12", "Free size")
 * @param values a measurement per column key
 */
public record ChartRow(String size, Map<String, MeasurementRange> values) {

    public ChartRow {
        Objects.requireNonNull(size, "size");
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
