package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;

class PlanChargePolicyTest {

    private static final Instant START = Instant.parse("2026-01-15T00:00:00Z");

    private static ToLongFunction<PlanChargePolicy.Clause> metrics(Map<RewardMetric, Long> values) {
        return c -> values.getOrDefault(c.metric(), 0L);
    }

    @Test
    void plainPolicyAlwaysCharges() {
        PlanChargePolicy.Decision d = PlanChargePolicy.ALWAYS.decide(START, START, metrics(Map.of()), false);
        assertThat(d.charge()).isTrue();
        assertThat(PlanChargePolicy.ALWAYS.isPlainCharge()).isTrue();
    }

    @Test
    void freeMonthsThenCharge() {
        PlanChargePolicy p = new PlanChargePolicy(3, PlanChargePolicy.Then.CHARGE, null, List.of(), null);
        PlanChargePolicy.Decision during = p.decide(START, Instant.parse("2026-03-20T00:00:00Z"), metrics(Map.of()), false);
        assertThat(during.charge()).isFalse();
        assertThat(during.reason()).contains("first 3 months").contains("Apr 15, 2026");
        assertThat(p.decide(START, Instant.parse("2026-04-15T00:00:00Z"), metrics(Map.of()), false).charge()).isTrue();
        assertThat(p.summary("$39/mo")).isEqualTo("First 3 months free, then $39/mo");
    }

    @Test
    void chargeWhenStartsOnlyOnceConditionIsMetAndStays() {
        PlanChargePolicy p = new PlanChargePolicy(0, PlanChargePolicy.Then.CHARGE_WHEN, null,
                List.of(new PlanChargePolicy.Clause(RewardMetric.GROSS_SALES, 100_000)), null);
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        PlanChargePolicy.Decision low = p.decide(START, now, metrics(Map.of(RewardMetric.GROSS_SALES, 42_000L)), false);
        assertThat(low.charge()).isFalse();
        assertThat(low.reason()).startsWith("Charges start once you");
        PlanChargePolicy.Decision met = p.decide(START, now, metrics(Map.of(RewardMetric.GROSS_SALES, 150_000L)), false);
        assertThat(met.charge()).isTrue();
        assertThat(met.nowMet()).isTrue();
        assertThat(p.decide(START, now, metrics(Map.of()), true).charge()).isTrue();
    }

    @Test
    void freeStretchThenConditionalStartWithAny() {
        PlanChargePolicy p = new PlanChargePolicy(2, PlanChargePolicy.Then.CHARGE_WHEN, PlanChargePolicy.Match.ANY,
                List.of(new PlanChargePolicy.Clause(RewardMetric.ACTIVE_LISTINGS, 20),
                        new PlanChargePolicy.Clause(RewardMetric.COMPLETED_ORDERS, 50)), null);
        assertThat(p.decide(START, Instant.parse("2026-02-01T00:00:00Z"), metrics(Map.of()), false).charge()).isFalse();
        Instant later = Instant.parse("2026-05-01T00:00:00Z");
        assertThat(p.decide(START, later, metrics(Map.of(RewardMetric.ACTIVE_LISTINGS, 3L)), false).charge()).isFalse();
        assertThat(p.decide(START, later, metrics(Map.of(RewardMetric.COMPLETED_ORDERS, 60L)), false).charge()).isTrue();
    }

    @Test
    void waiveWhileAllClausesHold() {
        PlanChargePolicy p = new PlanChargePolicy(0, PlanChargePolicy.Then.WAIVE_WHILE, PlanChargePolicy.Match.ALL,
                List.of(new PlanChargePolicy.Clause(RewardMetric.ACTIVE_LISTINGS, 20),
                        new PlanChargePolicy.Clause(RewardMetric.GROSS_SALES, 100_000)), 1);
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        PlanChargePolicy.Decision both = p.decide(START, now,
                metrics(Map.of(RewardMetric.ACTIVE_LISTINGS, 25L, RewardMetric.GROSS_SALES, 120_000L)), false);
        assertThat(both.charge()).isFalse();
        assertThat(both.reason()).startsWith("Waived while you").contains("last month");
        assertThat(p.decide(START, now, metrics(Map.of(RewardMetric.ACTIVE_LISTINGS, 25L)), false).charge()).isTrue();
    }

    @Test
    void validationRejectsIncompletePolicies() {
        assertThat(PlanChargePolicy.ALWAYS.validate()).isEmpty();
        PlanChargePolicy noCondition = new PlanChargePolicy(0, PlanChargePolicy.Then.WAIVE_WHILE, null, List.of(), null);
        assertThat(noCondition.validate()).containsKey("chargePolicy.clauses");
        PlanChargePolicy badFree = new PlanChargePolicy(99, PlanChargePolicy.Then.CHARGE, null, List.of(), 40);
        assertThat(badFree.validate()).containsKeys("chargePolicy.freeMonths", "chargePolicy.windowMonths");
    }
}
