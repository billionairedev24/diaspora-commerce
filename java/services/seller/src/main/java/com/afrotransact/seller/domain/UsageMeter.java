package com.afrotransact.seller.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Metered plan add-ons billed on the next plan invoice (S206). Quantities are whole <em>meter units</em>;
 * {@link #unitsPerDisplayUnit()} converts them to what statements show, and {@link #estimatedCents(long)} prices them
 * (the same figure the invoice line carries).
 */
public enum UsageMeter {
    /** FBA storage: hundredths of a cubic-foot-day; $0.87 per cubic foot per 30-day month = $0.029 per ft³-day. */
    FBA_STORAGE("FBA storage", "cubic-foot-day", 100, 29_000L),
    /** Sponsored products: cents of charged clicks, passed through at cost. */
    ADS_SPEND("Sponsored products", "USD", 100, 1_000_000L),
    /** AI credits: one credit = one US cent of model cost (ai-gateway). */
    AI_CREDITS("AI credits", "credit", 1, 10_000L);

    private static final BigDecimal MICROS_PER_CENT = BigDecimal.valueOf(10_000);

    private final String label;
    private final String displayUnit;
    private final int unitsPerDisplayUnit;
    private final long microsPerDisplayUnit;

    UsageMeter(String label, String displayUnit, int unitsPerDisplayUnit, long microsPerDisplayUnit) {
        this.label = label;
        this.displayUnit = displayUnit;
        this.unitsPerDisplayUnit = unitsPerDisplayUnit;
        this.microsPerDisplayUnit = microsPerDisplayUnit;
    }

    public String label() {
        return label;
    }

    public String displayUnit() {
        return displayUnit;
    }

    public int unitsPerDisplayUnit() {
        return unitsPerDisplayUnit;
    }

    /** Estimated price of one display unit in micro-dollars. */
    public long microsPerDisplayUnit() {
        return microsPerDisplayUnit;
    }

    /** Meter units for a quantity in display units (FBA: cubic-foot-days), rounded half up. */
    public long unitsOf(BigDecimal displayQuantity) {
        if (displayQuantity == null || displayQuantity.signum() <= 0) {
            return 0;
        }
        return displayQuantity.multiply(BigDecimal.valueOf(unitsPerDisplayUnit)).setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Display quantity of {@code units} meter units. */
    public BigDecimal display(long units) {
        return BigDecimal.valueOf(units).divide(BigDecimal.valueOf(unitsPerDisplayUnit), scale(), RoundingMode.UNNECESSARY);
    }

    /** Estimated amount of {@code units} meter units in cents, rounded half up. */
    public long estimatedCents(long units) {
        return BigDecimal.valueOf(units).multiply(BigDecimal.valueOf(microsPerDisplayUnit))
                .divide(BigDecimal.valueOf(unitsPerDisplayUnit).multiply(MICROS_PER_CENT), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private int scale() {
        return unitsPerDisplayUnit == 1 ? 0 : 2;
    }
}
