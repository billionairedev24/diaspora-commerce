package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Exchange rate captured on a document (PO date, invoice date): {@code unitsPerBase} units of {@code currency} buy one
 * unit of {@code baseCurrency}, e.g. 15.42 GHS per USD. Conversions round half up to the target's minor unit.
 */
public record FxRate(String currency, String baseCurrency, BigDecimal unitsPerBase, LocalDate asOf) {

    private static final MathContext PRECISION = new MathContext(20, RoundingMode.HALF_UP);

    public FxRate {
        currency = Currencies.require(currency);
        baseCurrency = Currencies.require(baseCurrency);
        Objects.requireNonNull(unitsPerBase, "unitsPerBase");
        if (unitsPerBase.signum() <= 0) {
            throw new RetailRuleViolation("retail.fx-invalid", "Exchange rate must be positive");
        }
        if (currency.equals(baseCurrency) && unitsPerBase.compareTo(BigDecimal.ONE) != 0) {
            throw new RetailRuleViolation("retail.fx-invalid", "Rate of the base currency must be 1");
        }
    }

    public static FxRate identity(String baseCurrency, LocalDate asOf) {
        return new FxRate(baseCurrency, baseCurrency, BigDecimal.ONE, asOf);
    }

    /** Converts an amount in {@code currency} minor units to base-currency minor units. */
    public long toBase(long minor) {
        if (currency.equals(baseCurrency)) {
            return minor;
        }
        BigDecimal base = Currencies.toMajor(minor, currency).divide(unitsPerBase, PRECISION);
        return Currencies.toMinor(base, baseCurrency);
    }

    /** Exact (unrounded) base-currency minor units, for allocations that must not round twice. */
    public BigDecimal toBaseExact(long minor) {
        if (currency.equals(baseCurrency)) {
            return BigDecimal.valueOf(minor);
        }
        return Currencies.toMajor(minor, currency).divide(unitsPerBase, PRECISION)
                .movePointRight(Currencies.fractionDigits(baseCurrency));
    }
}
