package com.afrotransact.catalog.domain.sizing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The length unit a size chart is written in; the other one is shown by conversion. */
public enum MeasurementUnit {
    CM("cm", new BigDecimal("1")),
    IN("in", new BigDecimal("2.54"));

    private final String symbol;
    private final BigDecimal centimetres;

    MeasurementUnit(String symbol, BigDecimal centimetres) {
        this.symbol = symbol;
        this.centimetres = centimetres;
    }

    public String symbol() {
        return symbol;
    }

    /** The unit shown next to this one (centimetres for inches and the other way round). */
    public MeasurementUnit other() {
        return this == CM ? IN : CM;
    }

    /** {@code value} in {@code target}, rounded to one decimal. */
    public BigDecimal convert(BigDecimal value, MeasurementUnit target) {
        if (target == this) {
            return value;
        }
        BigDecimal cm = value.multiply(centimetres);
        return cm.divide(target.centimetres, 1, RoundingMode.HALF_UP);
    }
}
