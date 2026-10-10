package com.afrotransact.deliveryruns.domain;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * An amount in minor units of an ISO 4217 currency.
 *
 * @param minor    amount in minor units (cents)
 * @param currency ISO 4217 code
 */
public record Money(long minor, String currency) {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public Money {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new RuleViolation("delivery-runs.invalid-currency", "Currency must be an ISO 4217 code");
        }
        if (minor < 0) {
            throw new RuleViolation("delivery-runs.invalid-amount", "Amounts cannot be negative");
        }
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    /** "7.99" (two decimals). */
    public String decimal() {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }
}
