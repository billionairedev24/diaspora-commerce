package com.afrotransact.retail.domain;

import java.math.BigDecimal;

/** Allowed invoice vs PO price (and amount) variance, in percent; default ±1%. */
public record MatchTolerance(BigDecimal pricePercent) {

    public MatchTolerance {
        if (pricePercent == null || pricePercent.signum() < 0) {
            throw new IllegalArgumentException("Tolerance must be non-negative");
        }
    }
}
