package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class LotCostTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    @Test
    void usdCostIsRateOneAndItsOwnUsdValue() {
        LotCost cost = LotCost.usd(250, DAY);
        assertThat(cost.rateToUsd()).isEqualByComparingTo("1");
        assertThat(cost.unitUsdMinor()).isEqualTo(250);
        assertThat(cost.rateSource()).isEqualTo(RateSource.BASE);
        assertThat(cost.components()).isEqualTo(CostComponents.ofTotal(250));
        assertThat(cost.isUsd()).isTrue();
    }

    @Test
    void foreignCostIsValuedInUsdAtTheRateAndKeepsTheOriginalAmount() {
        CostComponents parts = new CostComponents(10_000, 1_500, 400, 100);
        LotCost cost = LotCost.valued(12_000, "MXN", new BigDecimal("0.0577"), DAY, RateSource.TABLE, parts);
        assertThat(cost.unitMinor()).isEqualTo(12_000);
        assertThat(cost.currency()).isEqualTo("MXN");
        assertThat(cost.unitUsdMinor()).isEqualTo(692);
        assertThat(cost.components().freightMinor()).isEqualTo(1_500);
        assertThat(cost.isUsd()).isFalse();
    }

    @Test
    void rateMustBePositive() {
        assertThatThrownBy(() -> LotCost.valued(100, "MXN", BigDecimal.ZERO, DAY, RateSource.ENTERED, null))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.invalid-rate"));
        assertThatThrownBy(() -> LotCost.valued(100, "MXN", new BigDecimal("-0.05"), DAY, RateSource.ENTERED, null))
                .isInstanceOf(InventoryRuleViolation.class);
    }

    @Test
    void usDollarsCannotHaveAnotherRate() {
        assertThatThrownBy(() -> new LotCost(100, "USD", new BigDecimal("1.1"), DAY, RateSource.ENTERED, 110, null))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsKey("fxRateToUsd"));
    }

    @Test
    void currencyMustBeKnown() {
        assertThatThrownBy(() -> LotCost.valued(100, "ZZZ", BigDecimal.ONE, DAY, RateSource.ENTERED, null))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.invalid-currency"));
    }

    @Test
    void componentsMustAddUpToTheUnitCost() {
        assertThatThrownBy(() -> LotCost.valued(1_000, "GHS", new BigDecimal("0.065"), DAY, RateSource.ENTERED,
                new CostComponents(500, 100, 100, 100)))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.cost-components"));
    }

    @Test
    void componentsCannotBeNegative() {
        assertThatThrownBy(() -> new CostComponents(100, -1, 0, 0)).isInstanceOf(InventoryRuleViolation.class);
    }
}
