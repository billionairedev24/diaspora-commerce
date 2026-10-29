package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Reward rule evaluation: windows, gauges vs sums, eligibility and the once-per-seller rule (S296). */
class RewardEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);
    private static final SellerFacts ADA = new SellerFacts("org_ada", OrgKind.SELLER, "seller_growth",
            Instant.parse("2026-09-01T10:00:00Z"), true);

    @Test
    void windowsAreLifetimeRollingOrWithinDaysOfApproval() {
        LocalDate approved = LocalDate.of(2026, 9, 1);
        assertThat(RewardWindow.of(null, null, approved, TODAY)).isEqualTo(new RewardWindow(approved, TODAY, true));
        assertThat(RewardWindow.of(30, null, approved, TODAY))
                .isEqualTo(new RewardWindow(LocalDate.of(2026, 9, 12), TODAY, true));
        assertThat(RewardWindow.of(null, 60, approved, TODAY)).as("still inside the first 60 days")
                .isEqualTo(new RewardWindow(approved, TODAY, true));
        assertThat(RewardWindow.of(null, 30, approved, TODAY)).as("the first 30 days are over")
                .isEqualTo(new RewardWindow(approved, LocalDate.of(2026, 9, 30), false));
        assertThat(RewardWindow.of(7, 30, approved, TODAY)).as("both: the last 7 days of the first 30")
                .isEqualTo(new RewardWindow(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 30), false));
    }

    @Test
    void qualifiesOnceTheThresholdIsReachedInTime() {
        RewardRule sales = rule(RewardMetric.GROSS_SALES, 100_000, null, 60, List.of());
        RewardWindow w = RewardWindow.of(null, 60, LocalDate.of(2026, 9, 1), TODAY);
        RewardProgress below = RewardEvaluator.evaluate(sales, ADA, 64_000, w, false, NOW);
        assertThat(below.eligible()).isTrue();
        assertThat(below.qualifies()).isFalse();
        assertThat(below.percent()).isEqualTo(64);
        assertThat(below.label()).isEqualTo("$640 of $1,000 in sales → 1 month free");
        assertThat(RewardEvaluator.evaluate(sales, ADA, 100_000, w, false, NOW).qualifies()).isTrue();
        assertThat(RewardEvaluator.evaluate(sales, ADA, 150_000, w, true, NOW))
                .as("once per seller").satisfies(p -> {
                    assertThat(p.qualifies()).isFalse();
                    assertThat(p.reason()).isEqualTo("Already granted");
                });
    }

    @Test
    void aGaugeMustReachTheThresholdWhileTheWindowIsOpen() {
        RewardRule listings = rule(RewardMetric.ACTIVE_LISTINGS, 20, null, 30, List.of());
        RewardWindow closed = RewardWindow.of(null, 30, LocalDate.of(2026, 9, 1), TODAY);
        assertThat(RewardEvaluator.evaluate(listings, ADA, 25, closed, false, NOW).qualifies()).isFalse();
        RewardRule sales = rule(RewardMetric.GROSS_SALES, 100_000, null, 30, List.of());
        assertThat(RewardEvaluator.evaluate(sales, ADA, 120_000, closed, false, NOW).qualifies())
                .as("a sum over a closed window is final: sales made in time count").isTrue();
    }

    @Test
    void eligibilityFollowsKindPlanDatesAndLimits() {
        RewardWindow w = RewardWindow.of(null, null, LocalDate.of(2026, 9, 1), TODAY);
        assertThat(RewardEvaluator.evaluate(rule(RewardMetric.COMPLETED_ORDERS, 5, null, null, List.of("seller_pro")),
                ADA, 9, w, false, NOW).reason()).isEqualTo("Not on an eligible plan");
        RewardRule providers = new RewardRule(UUID.randomUUID(), "p", null, RewardRuleStatus.ACTIVE, OrgKind.PROVIDER,
                RewardMetric.COMPLETED_BOOKINGS, 5, null, null, RewardType.CREDIT, null, 2500L, null, null, List.of(),
                null, null, null, 0, "s", NOW, "s", NOW, 0);
        assertThat(RewardEvaluator.evaluate(providers, ADA, 9, w, false, NOW).reason()).isEqualTo("For providers only");
        RewardRule exhausted = new RewardRule(UUID.randomUUID(), "x", null, RewardRuleStatus.ACTIVE, null,
                RewardMetric.COMPLETED_ORDERS, 5, null, null, RewardType.PERCENT_OFF, null, null, BigDecimal.TEN, 3,
                List.of(), null, null, 2, 2, "s", NOW, "s", NOW, 0);
        assertThat(RewardEvaluator.evaluate(exhausted, ADA, 9, w, false, NOW).reason()).isEqualTo("No grants left");
        RewardRule ended = new RewardRule(UUID.randomUUID(), "x", null, RewardRuleStatus.ACTIVE, null,
                RewardMetric.COMPLETED_ORDERS, 5, null, null, RewardType.FREE_DAYS, 30, null, null, null, List.of(),
                null, NOW.minusSeconds(60), null, 0, "s", NOW, "s", NOW, 0);
        assertThat(RewardEvaluator.evaluate(ended, ADA, 9, w, false, NOW).eligible()).isFalse();
        SellerFacts free = new SellerFacts("org_free", OrgKind.SELLER, "seller_essentials", ADA.approvedAt(), false);
        assertThat(RewardEvaluator.evaluate(rule(RewardMetric.COMPLETED_ORDERS, 5, null, null, List.of()), free, 9, w,
                false, NOW).reason()).isEqualTo("Not on a paid plan");
        assertThat(rule(RewardMetric.GROSS_SALES, 100_000, 30, 60, List.of()).conditionLabel())
                .isEqualTo("$1,000 in sales in 30 days within 60 days of approval");
    }

    @Test
    void ruleInputKeepsOnlyTheRewardTypesFields() {
        RewardRuleInput in = new RewardRuleInput("List 20", null, null, RewardMetric.ACTIVE_LISTINGS, 20L, null, null,
                RewardType.FREE_DAYS, 30, 2500L, BigDecimal.TEN, 3, List.of(), null, null, null, null);
        in.validate();
        assertThat(in.rewardFreeDays()).isEqualTo(30);
        assertThat(in.rewardCreditCents()).isNull();
        assertThat(in.rewardPercentOff()).isNull();
    }

    private static RewardRule rule(RewardMetric metric, long threshold, Integer window, Integer within,
                                   List<String> plans) {
        return new RewardRule(UUID.randomUUID(), "rule", null, RewardRuleStatus.ACTIVE, null, metric, threshold,
                window, within, RewardType.FREE_DAYS, 30, null, null, null, plans, null, null, null, 0, "staff", NOW,
                "staff", NOW, 0);
    }
}
