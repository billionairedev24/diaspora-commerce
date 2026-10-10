package com.afrotransact.orders.port;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaxRateTest {

    @Test
    void taxIsRoundedHalfUpToTheCent() {
        TaxCalculator.Rate texas = new TaxCalculator.Rate(825, "US-TX");
        assertThat(texas.taxOf(2500)).isEqualTo(206);
        assertThat(texas.taxOf(1000)).isEqualTo(83);
        assertThat(texas.taxOf(0)).isZero();
        assertThat(new TaxCalculator.Rate(0, "US-OR").taxOf(9999)).isZero();
        assertThat(new TaxCalculator.Rate(1000, "X").taxOf(5)).isEqualTo(1);
        assertThat(new TaxCalculator.Rate(1000, "X").taxOf(4)).isZero();
    }
}
