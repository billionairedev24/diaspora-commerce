package com.afrotransact.catalog.domain.sizing;

import java.util.Objects;

/**
 * One measurement of a size chart (bust, waist, foot length ...).
 *
 * @param key   stable key rows refer to; lower-case letters, digits and underscores
 * @param label what the shopper reads ("Bust")
 * @param unit  the unit its values are written in: always the chart's unit
 */
public record ChartColumn(String key, String label, MeasurementUnit unit) {

    public ChartColumn {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
    }
}
