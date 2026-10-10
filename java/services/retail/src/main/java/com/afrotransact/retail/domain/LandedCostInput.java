package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One receipt (or ASN) line to cost: units received, FOB value already converted to base currency at the PO rate,
 * unit weight for weight-based charges and the item's HTS duty rate (used when no explicit DUTY charge exists).
 */
public record LandedCostInput(int lineNo, String sku, int qty, long fobValueMinor, int unitWeightGrams,
                              BigDecimal dutyRatePercent) {

    public LandedCostInput {
        Objects.requireNonNull(sku, "sku");
        if (qty < 0 || fobValueMinor < 0 || unitWeightGrams < 0) {
            throw new IllegalArgumentException("Line " + lineNo + ": quantities and values cannot be negative");
        }
        dutyRatePercent = dutyRatePercent == null ? BigDecimal.ZERO : dutyRatePercent;
    }
}
