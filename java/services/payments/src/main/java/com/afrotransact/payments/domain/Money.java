package com.afrotransact.payments.domain;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * An amount of money: a signed {@code long} of minor units (cents) and an ISO 4217 currency. Never a floating point
 * value. Arithmetic is exact and refuses to mix currencies; overflow throws.
 *
 * @param minor    amount in minor units (may be negative for movements and balances)
 * @param currency upper-case ISO 4217 code, for example {@code USD}
 */
public record Money(long minor, String currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        currency = currency.trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw new PaymentsRuleViolation("payments.invalid-currency", "Currency must be an ISO 4217 code");
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw new PaymentsRuleViolation("payments.invalid-currency", "Unknown currency " + currency);
        }
    }

    public static Money of(long minor, String currency) {
        return new Money(minor, currency);
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money plus(long otherMinor) {
        return new Money(Math.addExact(minor, otherMinor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    public Money abs() {
        return minor < 0 ? negate() : this;
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(minor, factor), currency);
    }

    /**
     * {@code bps} basis points of this amount, rounded half up (away from zero for negatives): 1 bp = 0.01 %.
     * Exact integer arithmetic; the amount must be at most ~9.2e14 minor units.
     */
    public Money basisPoints(int bps) {
        if (bps < 0) {
            throw new IllegalArgumentException("bps must be >= 0");
        }
        long product = Math.multiplyExact(Math.abs(minor), (long) bps);
        long rounded = (product + 5_000) / 10_000;
        return new Money(minor < 0 ? -rounded : rounded, currency);
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isPositive() {
        return minor > 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public boolean greaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean lessThan(Money other) {
        return compareTo(other) < 0;
    }

    public Money min(Money other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public Money max(Money other) {
        return compareTo(other) >= 0 ? this : other;
    }

    public void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new PaymentsRuleViolation("payments.currency-mismatch",
                    "Cannot combine " + currency + " and " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    /** Human-readable form for logs and messages, e.g. {@code USD 12.34} (no rounding: integer split). */
    @Override
    public String toString() {
        int digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        long unit = 1;
        for (int i = 0; i < digits; i++) {
            unit *= 10;
        }
        long abs = Math.abs(minor);
        String body = digits == 0 ? Long.toString(abs)
                : (abs / unit) + "." + String.format(Locale.ROOT, "%0" + digits + "d", abs % unit);
        return currency + " " + (minor < 0 ? "-" : "") + body;
    }
}
