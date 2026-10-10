package com.afrotransact.catalog.domain.barcode;

import java.util.Objects;

/**
 * AT-FN unit label code: {@code ATF} + 7 base-36 characters, issued once per stock owner and variant ATIN so units
 * of different sellers are never mixed. Platform (1P) stock without a vendor GTIN gets one at receiving.
 */
public record UnitLabelCode(String value) {

    public UnitLabelCode {
        Objects.requireNonNull(value, "value");
        if (!value.matches("ATF[0-9A-Z]{7}")) {
            throw new IllegalArgumentException("Invalid AT-FN unit label code " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
