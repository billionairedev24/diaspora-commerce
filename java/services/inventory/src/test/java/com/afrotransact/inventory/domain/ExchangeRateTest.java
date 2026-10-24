package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ExchangeRateTest {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    private static ExchangeRate rate(String currency, String value) {
        return new ExchangeRate(currency, DAY, new BigDecimal(value), null, "ops", NOW, NOW, 0);
    }

    @Test
    void aRateIsPositiveForAKnownCurrencyOtherThanUsd() {
        assertThat(rate("MXN", "0.0577").rateToUsd()).isEqualByComparingTo("0.0577");
        assertThatThrownBy(() -> rate("MXN", "0")).isInstanceOfSatisfying(InventoryRuleViolation.class,
                ex -> assertThat(ex.code()).isEqualTo("inventory.invalid-rate"));
        assertThatThrownBy(() -> rate("MXN", "-1")).isInstanceOf(InventoryRuleViolation.class);
        assertThatThrownBy(() -> rate("USD", "1")).isInstanceOfSatisfying(InventoryRuleViolation.class,
                ex -> assertThat(ex.code()).isEqualTo("inventory.invalid-currency"));
        assertThatThrownBy(() -> rate("ZZZ", "1")).isInstanceOf(InventoryRuleViolation.class);
    }

    @Test
    void ratesKeepAtMostTenDecimals() {
        assertThat(rate("NGN", "0.0006666667").rateToUsd()).isEqualByComparingTo("0.0006666667");
        assertThatThrownBy(() -> rate("NGN", "0.00066666667")).isInstanceOfSatisfying(InventoryRuleViolation.class,
                ex -> assertThat(ex.fieldErrors()).containsKey("rateToUsd"));
    }

    @Test
    void tidyDropsTrailingZerosAndNeverUsesExponents() {
        assertThat(ExchangeRate.tidy(new BigDecimal("0.0577000000")).toPlainString()).isEqualTo("0.0577");
        assertThat(ExchangeRate.tidy(new BigDecimal("17.000")).toString()).isEqualTo("17");
        assertThat(ExchangeRate.tidy(new BigDecimal("1E+1")).toString()).isEqualTo("10");
    }
}
