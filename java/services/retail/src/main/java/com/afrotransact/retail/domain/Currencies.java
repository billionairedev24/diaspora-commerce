package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/** ISO 4217 helpers: amounts are always integer minor units of their currency (XOF has none, USD has two). */
public final class Currencies {

    private Currencies() {
    }

    /** Upper-cases and validates an ISO 4217 code. */
    public static String require(String code) {
        if (code == null || code.isBlank()) {
            throw new RetailRuleViolation("retail.currency-invalid", "Currency is required");
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(c);
        } catch (IllegalArgumentException e) {
            throw new RetailRuleViolation("retail.currency-invalid", "Unknown currency " + code);
        }
        return c;
    }

    /** Number of minor-unit digits (2 for USD/GHS/NGN, 0 for XOF). */
    public static int fractionDigits(String code) {
        return Math.max(0, Currency.getInstance(code).getDefaultFractionDigits());
    }

    /** Minor units → major units as an exact decimal. */
    public static BigDecimal toMajor(long minor, String code) {
        return BigDecimal.valueOf(minor).movePointLeft(fractionDigits(code));
    }

    /** Major units → minor units, rounding half up. */
    public static long toMinor(BigDecimal major, String code) {
        return major.movePointRight(fractionDigits(code)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
