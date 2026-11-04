package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class UsdConversionTest {

    @Test
    void convertsMinorUnitsOfTwoDecimalCurrenciesToCents() {
        // MXN 120.00 at 0.0577 USD each = $6.924, rounded to the cent
        assertThat(UsdConversion.toUsdMinor(12_000, "MXN", new BigDecimal("0.0577"))).isEqualTo(692);
        // NGN 1,500.00 at 0.00066667 = $1.00
        assertThat(UsdConversion.toUsdMinor(150_000, "NGN", new BigDecimal("0.00066667"))).isEqualTo(100);
    }

    @Test
    void currenciesWithoutMinorUnitsAreHandledInWholeUnits() {
        assertThat(UsdConversion.minorDigits("XOF")).isZero();
        assertThat(UsdConversion.minorDigits("USD")).isEqualTo(2);
        // XOF 6,000 at 0.00165 USD = $9.90
        assertThat(UsdConversion.toUsdMinor(6_000, "XOF", new BigDecimal("0.00165"))).isEqualTo(990);
    }

    @Test
    void roundsHalfEvenToTheCent() {
        assertThat(UsdConversion.toUsdMinor(5, "GHS", new BigDecimal("0.5"))).as("2.5 cents").isEqualTo(2);
        assertThat(UsdConversion.toUsdMinor(7, "GHS", new BigDecimal("0.5"))).as("3.5 cents").isEqualTo(4);
    }

    @Test
    void usDollarsAtRateOneAreUnchanged() {
        assertThat(UsdConversion.toUsdMinor(1_999, "USD", BigDecimal.ONE)).isEqualTo(1_999);
    }

    @Test
    void recognisesIsoCodesOnly() {
        assertThat(UsdConversion.isCurrency("MXN")).isTrue();
        assertThat(UsdConversion.isCurrency("mxn")).isFalse();
        assertThat(UsdConversion.isCurrency("ZZZ")).isFalse();
        assertThat(UsdConversion.isCurrency(null)).isFalse();
    }
}
