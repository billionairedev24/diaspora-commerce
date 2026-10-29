package com.afrotransact.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** Converts amounts in any ISO 4217 currency to US cents. Rounds half-even to the cent. */
public final class UsdConversion {

    /** Minor-unit digits of the US dollar. */
    public static final int USD_DIGITS = 2;

    private UsdConversion() {
    }

    /** Minor-unit digits of {@code currency} (2 for USD, MXN and NGN; 0 for XOF). */
    public static int minorDigits(String currency) {
        return Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
    }

    /** True for an upper-case code of a currency money is paid in. */
    public static boolean isCurrency(String code) {
        if (code == null || !code.matches("[A-Z]{3}")) {
            return false;
        }
        try {
            // XXX ("no currency") and the testing/fund codes have no minor-unit digits (-1): not payable.
            return Currency.getInstance(code).getDefaultFractionDigits() >= 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** {@code minor} minor units of {@code currency} in US cents at {@code rateToUsd} (US dollars per unit). */
    public static long toUsdMinor(long minor, String currency, BigDecimal rateToUsd) {
        return BigDecimal.valueOf(minor).movePointLeft(minorDigits(currency)).multiply(rateToUsd)
                .movePointRight(USD_DIGITS).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }
}
