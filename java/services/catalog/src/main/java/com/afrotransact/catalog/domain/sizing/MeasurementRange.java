package com.afrotransact.catalog.domain.sizing;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A measurement of one size: a single value (min equals max) or a range from min to max, in the chart's unit.
 */
public record MeasurementRange(BigDecimal min, BigDecimal max) {

    public MeasurementRange {
        Objects.requireNonNull(min, "min");
        max = max == null ? min : max;
    }

    public boolean single() {
        return min.compareTo(max) == 0;
    }

    /** The range in another unit, rounded to one decimal. */
    public MeasurementRange convert(MeasurementUnit from, MeasurementUnit to) {
        return new MeasurementRange(from.convert(min, to), from.convert(max, to));
    }
}
