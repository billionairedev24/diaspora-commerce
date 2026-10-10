package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void exactArithmeticInMinorUnits() {
        Money a = Money.of(1999, "usd");
        assertThat(a.currency()).isEqualTo("USD");
        assertThat(a.plus(Money.of(1, "USD"))).isEqualTo(Money.of(2000, "USD"));
        assertThat(a.minus(Money.of(2000, "USD")).minor()).isEqualTo(-1);
        assertThat(a.times(3).minor()).isEqualTo(5997);
        assertThat(a.negate().abs()).isEqualTo(a);
        assertThat(a.toString()).isEqualTo("USD 19.99");
        assertThat(Money.of(-5, "USD").toString()).isEqualTo("USD -0.05");
        assertThat(Money.of(500, "JPY").toString()).isEqualTo("JPY 500");
    }

    @Test
    void basisPointsRoundHalfUp() {
        assertThat(Money.of(1000, "USD").basisPoints(800).minor()).isEqualTo(80);
        assertThat(Money.of(1005, "USD").basisPoints(1000).minor()).isEqualTo(101);
        assertThat(Money.of(1004, "USD").basisPoints(1000).minor()).isEqualTo(100);
        assertThat(Money.of(-1005, "USD").basisPoints(1000).minor()).isEqualTo(-101);
        assertThat(Money.of(12_345, "USD").basisPoints(290).minor()).isEqualTo(358);
    }

    @Test
    void refusesMixedCurrenciesAndBadCodes() {
        assertThatThrownBy(() -> Money.of(1, "USD").plus(Money.of(1, "EUR")))
                .isInstanceOf(PaymentsRuleViolation.class).hasMessageContaining("USD and EUR");
        assertThatThrownBy(() -> Money.of(1, "US")).isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> Money.of(1, "XYZ")).isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "USD").plus(Money.of(1, "USD")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void comparesWithinOneCurrency() {
        assertThat(Money.of(5, "USD").greaterThan(Money.of(4, "USD"))).isTrue();
        assertThat(Money.of(5, "USD").min(Money.of(4, "USD")).minor()).isEqualTo(4);
        assertThat(Money.of(5, "USD").max(Money.of(4, "USD")).minor()).isEqualTo(5);
    }
}
