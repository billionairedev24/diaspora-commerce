package com.afrotransact.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.inventory.domain.CostComponents;
import com.afrotransact.inventory.domain.ExchangeRate;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.LotCost;
import com.afrotransact.inventory.domain.RateSource;
import org.junit.jupiter.api.Test;

class LotCostValuerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    private final InMemoryExchangeRates rates = new InMemoryExchangeRates();
    private final InventorySettings settings = new InventorySettings("org_retail", Duration.ofMinutes(15), 7, 200,
            new BigDecimal("0.25"), List.of("USD", "MXN", "NGN", "XOF"));
    private final LotCostValuer valuer = new LotCostValuerImpl(rates, settings);

    private static ReceiveCommand.Line line(long cost, String currency, BigDecimal rate, LocalDate rateDate,
                                            Long usd, CostComponents parts) {
        return new ReceiveCommand.Line("AT-JOL-6", "L-1", null, null, 10, 0, null, cost, currency, null, null, rate,
                rateDate, usd, parts);
    }

    private void tableRate(String currency, LocalDate day, String rate) {
        rates.insert(new ExchangeRate(currency, day, new BigDecimal(rate), "Banxico FIX", "ops", NOW, NOW, 0));
    }

    @Test
    void usDollarsNeedNoRateAndAreValuedAtOne() {
        LotCost cost = valuer.value(List.of(line(250, "USD", null, null, null, null)), TODAY).getFirst();
        assertThat(cost.unitUsdMinor()).isEqualTo(250);
        assertThat(cost.rateSource()).isEqualTo(RateSource.BASE);
        assertThat(cost.rateToUsd()).isEqualByComparingTo("1");
        assertThat(cost.rateDate()).isEqualTo(TODAY);
    }

    @Test
    void theRateTheReceiverEntersWinsOverTheTable() {
        tableRate("MXN", TODAY, "0.0500");
        LotCost cost = valuer.value(List.of(line(12_000, "MXN", new BigDecimal("0.0577"), TODAY.minusDays(1), null,
                null)), TODAY).getFirst();
        assertThat(cost.rateSource()).isEqualTo(RateSource.ENTERED);
        assertThat(cost.rateToUsd()).isEqualByComparingTo("0.0577");
        assertThat(cost.rateDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(cost.unitUsdMinor()).isEqualTo(692);
        assertThat(cost.unitMinor()).as("the original amount is kept").isEqualTo(12_000);
    }

    @Test
    void withoutARateTheLatestTableRateOnOrBeforeTodayIsTheDefault() {
        tableRate("MXN", TODAY.minusDays(10), "0.0550");
        tableRate("MXN", TODAY.minusDays(2), "0.0577");
        tableRate("MXN", TODAY.plusDays(1), "0.0600");
        LotCost cost = valuer.value(List.of(line(12_000, "MXN", null, null, null, null)), TODAY).getFirst();
        assertThat(cost.rateSource()).isEqualTo(RateSource.TABLE);
        assertThat(cost.rateToUsd()).isEqualByComparingTo("0.0577");
        assertThat(cost.rateDate()).isEqualTo(TODAY.minusDays(2));
        assertThat(cost.unitUsdMinor()).isEqualTo(692);
    }

    @Test
    void aCurrencyWithoutAnyRateIsRejectedWithTheFieldToFix() {
        assertThatThrownBy(() -> valuer.value(List.of(line(100, "NGN", null, null, null, null)), TODAY))
                .isInstanceOfSatisfying(InventoryRuleViolation.class, ex -> {
                    assertThat(ex.code()).isEqualTo("inventory.invalid-cost");
                    assertThat(ex.fieldErrors()).containsOnlyKeys("lines[0].fxRateToUsd");
                });
    }

    @Test
    void aCurrencyOutsideTheConfiguredListIsRejected() {
        assertThatThrownBy(() -> valuer.value(List.of(line(100, "JPY", BigDecimal.ONE, null, null, null)), TODAY))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsOnlyKeys("lines[0].currency"));
    }

    @Test
    void ratesMustBePositiveNotFutureAndNotOverPrecise() {
        List<ReceiveCommand.Line> lines = List.of(
                line(100, "MXN", BigDecimal.ZERO, null, null, null),
                line(100, "MXN", new BigDecimal("0.05"), TODAY.plusDays(1), null, null),
                line(100, "MXN", new BigDecimal("0.00000000001"), null, null, null),
                line(100, "USD", new BigDecimal("1.2"), null, null, null));
        assertThatThrownBy(() -> valuer.value(lines, TODAY)).isInstanceOfSatisfying(InventoryRuleViolation.class,
                ex -> assertThat(ex.fieldErrors()).containsOnlyKeys("lines[0].fxRateToUsd", "lines[1].fxRateDate",
                        "lines[2].fxRateToUsd", "lines[3].fxRateToUsd"));
    }

    @Test
    void componentsMustAddUpToTheUnitCostAndAreKeptWhenTheyDo() {
        CostComponents parts = new CostComponents(10_000, 1_500, 400, 100);
        LotCost cost = valuer.value(List.of(line(12_000, "MXN", new BigDecimal("0.0577"), null, null, parts)), TODAY)
                .getFirst();
        assertThat(cost.components()).isEqualTo(parts);
        assertThatThrownBy(() -> valuer.value(List.of(line(11_000, "MXN", new BigDecimal("0.0577"), null, null,
                parts)), TODAY)).isInstanceOfSatisfying(InventoryRuleViolation.class,
                ex -> assertThat(ex.fieldErrors()).containsOnlyKeys("lines[0].costComponents"));
    }

    @Test
    void aSenderThatAlreadyValuedTheCostKeepsItsUsdValueExactly() {
        LotCost cost = valuer.value(List.of(line(12_000, "MXN", new BigDecimal("0.0577"), TODAY, 693L, null)), TODAY)
                .getFirst();
        assertThat(cost.unitUsdMinor()).isEqualTo(693);
        assertThat(cost.rateSource()).isEqualTo(RateSource.ENTERED);
        assertThatThrownBy(() -> valuer.value(List.of(line(12_000, "MXN", null, null, 693L, null)), TODAY))
                .as("a USD value needs the rate that goes with it").isInstanceOf(InventoryRuleViolation.class);
    }

    @Test
    void everyBadLineIsReportedTogether() {
        List<ReceiveCommand.Line> lines = List.of(line(100, "USD", null, null, null, null),
                line(100, "JPY", null, null, null, null), line(100, "NGN", null, null, null, null));
        assertThatThrownBy(() -> valuer.value(lines, TODAY)).isInstanceOfSatisfying(InventoryRuleViolation.class,
                ex -> assertThat(ex.fieldErrors()).containsOnlyKeys("lines[1].currency", "lines[2].fxRateToUsd"));
    }
}
