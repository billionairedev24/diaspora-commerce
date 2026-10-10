package com.afrotransact.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BudgetPolicyTest {

    private final DefaultBudgetPolicy policy = new DefaultBudgetPolicy();

    @Test
    void allowsBelowEveryLimit() {
        BudgetLimits limits = new BudgetLimits(1_000, 500_000, 10_000_000, false, 1.5);
        assertThat(policy.decide(new UsageTotals(999, 499_999), new UsageTotals(0, 9_999_999), limits))
                .isEqualTo(BudgetDecision.ALLOW);
    }

    @Test
    void deniesAtTheLimitWhenDegradingIsOff() {
        BudgetLimits limits = new BudgetLimits(1_000, 0, 0, false, 1.5);
        BudgetDecision d = policy.decide(new UsageTotals(1_000, 0), UsageTotals.ZERO, limits);
        assertThat(d.denied()).isTrue();
        assertThat(d.scope()).isEqualTo("user-tokens");
    }

    @Test
    void degradesBetweenSoftAndHardLimitThenDenies() {
        BudgetLimits limits = new BudgetLimits(0, 1_000_000, 0, true, 1.5);
        assertThat(policy.decide(new UsageTotals(0, 1_200_000), UsageTotals.ZERO, limits).degrade()).isTrue();
        BudgetDecision hard = policy.decide(new UsageTotals(0, 1_500_000), UsageTotals.ZERO, limits);
        assertThat(hard.denied()).isTrue();
        assertThat(hard.scope()).isEqualTo("user-cost");
    }

    @Test
    void featureWideLimitAppliesToEveryone() {
        BudgetLimits limits = new BudgetLimits(0, 0, 50_000_000, false, 1.0);
        BudgetDecision d = policy.decide(UsageTotals.ZERO, new UsageTotals(10, 50_000_000), limits);
        assertThat(d.denied()).isTrue();
        assertThat(d.scope()).isEqualTo("feature-cost");
    }

    @Test
    void worstVerdictWins() {
        BudgetLimits limits = new BudgetLimits(100, 0, 1_000, true, 2.0);
        BudgetDecision d = policy.decide(new UsageTotals(150, 0), new UsageTotals(0, 5_000), limits);
        assertThat(d.denied()).isTrue();
        assertThat(d.scope()).isEqualTo("feature-cost");
    }

    @Test
    void zeroMeansUnlimited() {
        assertThat(policy.decide(new UsageTotals(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2),
                new UsageTotals(0, Long.MAX_VALUE / 2), BudgetLimits.unlimited())).isEqualTo(BudgetDecision.ALLOW);
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThatThrownBy(() -> new BudgetLimits(-1, 0, 0, false, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetLimits(0, 0, 0, true, 0.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void costIsEstimatedInMicrosAndRoundedUp() {
        PriceTable prices = new PriceTable(Map.of(
                "anthropic/claude-sonnet-4.5", new PriceTable.ModelPrice(new BigDecimal("3"), new BigDecimal("15")),
                "openai/gpt-5-nano", new PriceTable.ModelPrice(new BigDecimal("0.05"), new BigDecimal("0.40"))),
                new PriceTable.ModelPrice(new BigDecimal("15"), new BigDecimal("75")));
        // 1,000 prompt tokens × $3/M + 500 completion × $15/M = $0.0105 = 10,500 micro-dollars
        assertThat(prices.costMicros("anthropic/claude-sonnet-4.5", new TokenUsage(1_000, 500))).isEqualTo(10_500);
        // 1 prompt token at $0.05/M = 0.05 micro-dollars → rounded up to 1
        assertThat(prices.costMicros("openai/gpt-5-nano", new TokenUsage(1, 0))).isEqualTo(1);
        // unknown model uses the pessimistic default
        assertThat(prices.costMicros("someone/new-model", new TokenUsage(1_000_000, 0))).isEqualTo(15_000_000);
        assertThat(prices.cheapest(List.of("anthropic/claude-sonnet-4.5", "openai/gpt-5-nano")))
                .contains("openai/gpt-5-nano");
    }
}
