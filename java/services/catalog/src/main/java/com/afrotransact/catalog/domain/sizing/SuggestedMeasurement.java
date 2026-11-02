package com.afrotransact.catalog.domain.sizing;

import java.util.Objects;

/**
 * A measurement a product type suggests for its size charts (bust, waist, foot length …). Sellers can use it, rename
 * it or add their own columns.
 *
 * @param key   stable machine key, lower-case letters, digits and underscores
 * @param label what the column is called
 * @param unit  the default unit of this measurement; null = the chart's own unit
 * @param help  one line telling the seller how to measure it; may be null
 */
public record SuggestedMeasurement(String key, String label, MeasurementUnit unit, String help) {

    public SuggestedMeasurement {
        key = key == null ? "" : key.strip();
        Objects.requireNonNull(label, "label");
        help = help == null || help.isBlank() ? null : help.strip();
    }
}
