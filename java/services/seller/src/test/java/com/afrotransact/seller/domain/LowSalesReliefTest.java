package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * S299: "less than $1,000 in sales in the first 30 days of the subscription → 30 free days", decided once, 24 h
 * before the window (the 30-day trial) ends, so the free days move trial_end before Stripe creates the first invoice.
 */
class LowSalesReliefTest {

    private static final Instant APPROVED = Instant.parse("2026-09-28T09:00:00Z");
    private static final Instant STARTED = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant TRIAL_END = STARTED.plus(Duration.ofDays(30));
    private static final Duration LEAD = Duration.ofHours(24);
    private static final SellerFacts ADA = new SellerFacts("org_ada", OrgKind.SELLER, "seller_growth", APPROVED, true,
            STARTED, TRIAL_END);

    @Test
    void theWindowStartsAtTheSubscriptionAndIsCheckedADayBeforeTheTrialEnds() {
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        RewardWindow w = RewardWindow.closing(ADA.anchor(RewardWindowAnchor.SUBSCRIPTION_START), 30, TRIAL_END, LEAD,
                now);
        assertThat(w.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(w.to()).as("sales so far").isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(w.closesAt()).isEqualTo(TRIAL_END);
        assertThat(w.checkAt()).isEqualTo(Instant.parse("2026-10-30T10:00:00Z"));
        assertThat(w.open()).isTrue();
        assertThat(w.due(now)).isFalse();

        RewardWindow atCheck = RewardWindow.closing(STARTED, 30, TRIAL_END, LEAD, w.checkAt());
        assertThat(atCheck.to()).as("sales after the check are not counted").isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(atCheck.due(w.checkAt())).isTrue();
        assertThat(atCheck.due(TRIAL_END.plus(LEAD))).as("missed once the lead has passed after the window")
                .isFalse();

        RewardWindow approval = RewardWindow.closing(ADA.anchor(RewardWindowAnchor.APPROVAL), 30, TRIAL_END, LEAD,
                now);
        assertThat(approval.from()).as("anchored at approval").isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(approval.checkAt()).isEqualTo(Instant.parse("2026-10-27T09:00:00Z"));
    }

    @Test
    void whenStripeWouldInvoiceBeforeTheWindowClosesTheCheckRunsBeforeTheRenewal() {
        // A 31-day window over a 30-day trial: the first invoice comes a day before the window closes.
        RewardWindow w = RewardWindow.closing(STARTED, 31, TRIAL_END, LEAD, STARTED);
        assertThat(w.closesAt()).isEqualTo(STARTED.plus(Duration.ofDays(31)));
        assertThat(w.checkAt()).isEqualTo(TRIAL_END.minus(LEAD));
        // A renewal well before the window's check is not moved for (the window is still open then).
        RewardWindow longer = RewardWindow.closing(STARTED, 60, TRIAL_END, LEAD, STARTED);
        assertThat(longer.checkAt()).isEqualTo(STARTED.plus(Duration.ofDays(60)).minus(LEAD));
        RewardWindow custom = RewardWindow.closing(STARTED, 30, TRIAL_END, Duration.ofHours(6), STARTED);
        assertThat(custom.checkAt()).as("configurable lead").isEqualTo(TRIAL_END.minus(Duration.ofHours(6)));
    }

    @Test
    void exactlyTheThresholdIsNotLessThanIt() {
        RewardRule rule = relief();
        Instant due = TRIAL_END.minus(LEAD).plusSeconds(60);
        RewardWindow w = RewardWindow.closing(STARTED, 30, TRIAL_END, LEAD, due);
        assertThat(RewardEvaluator.evaluate(rule, ADA, 100_000, w, false, false, due).qualifies())
                .as("$1,000.00 is not less than $1,000").isFalse();
        RewardProgress under = RewardEvaluator.evaluate(rule, ADA, 99_999, w, false, false, due);
        assertThat(under.qualifies()).as("$999.99").isTrue();
        assertThat(under.pending()).isFalse();
        assertThat(RewardEvaluator.evaluate(rule, ADA, 0, w, false, false, due).qualifies()).as("no sales at all")
                .isTrue();
    }

    @Test
    void nothingIsDecidedBeforeTheCheckAndItIsDecidedOnce() {
        RewardRule rule = relief();
        Instant early = Instant.parse("2026-10-12T12:00:00Z");
        RewardWindow open = RewardWindow.closing(STARTED, 30, TRIAL_END, LEAD, early);
        RewardProgress pending = RewardEvaluator.evaluate(rule, ADA, 20_000, open, false, false, early);
        assertThat(pending.qualifies()).as("low so far, but the window is open").isFalse();
        assertThat(pending.pending()).isTrue();
        assertThat(pending.eligible()).isTrue();
        assertThat(pending.checkAt()).isEqualTo(TRIAL_END.minus(LEAD));
        assertThat(pending.label()).isEqualTo("$200 in sales so far (less than $1,000 → 1 month free)");

        Instant due = TRIAL_END.minus(LEAD);
        RewardWindow w = RewardWindow.closing(STARTED, 30, TRIAL_END, LEAD, due);
        assertThat(RewardEvaluator.evaluate(rule, ADA, 20_000, w, false, true, due)).satisfies(p -> {
            assertThat(p.qualifies()).isFalse();
            assertThat(p.reason()).isEqualTo("Already checked");
        });
        assertThat(RewardEvaluator.evaluate(rule, ADA, 20_000, w, true, false, due).reason())
                .isEqualTo("Already granted");
        Instant late = TRIAL_END.plus(LEAD);
        assertThat(RewardEvaluator.evaluate(rule, ADA, 20_000, RewardWindow.closing(STARTED, 30, TRIAL_END, LEAD,
                late), false, false, late).reason()).as("a missed check grants nothing").isEqualTo("Window closed");
        SellerFacts notStarted = new SellerFacts("org_new", OrgKind.SELLER, "seller_growth", APPROVED, true, null,
                null);
        assertThat(RewardEvaluator.evaluate(rule, notStarted, 0, new RewardWindow(LocalDate.of(2026, 10, 12),
                LocalDate.of(2026, 10, 12), true), false, false, early).reason())
                .isEqualTo("Subscription not started");
    }

    @Test
    void anAtLeastRuleCanAlsoWaitForTheWindowToClose() {
        RewardRule rule = new RewardRule(UUID.randomUUID(), "Big first month", null, RewardRuleStatus.ACTIVE, null,
                RewardMetric.GROSS_SALES, 500_000, null, 30, RewardComparison.AT_LEAST,
                RewardWindowAnchor.SUBSCRIPTION_START, true, 24, RewardType.CREDIT, null, 5_000L, null, null,
                List.of(), null, null, null, 0, "staff", STARTED, "staff", STARTED, 0);
        Instant early = Instant.parse("2026-10-12T12:00:00Z");
        RewardProgress reached = RewardEvaluator.evaluate(rule, ADA, 600_000, RewardWindow.closing(STARTED, 30,
                TRIAL_END, LEAD, early), false, false, early);
        assertThat(reached.qualifies()).as("reached early, decided at window end").isFalse();
        assertThat(reached.pending()).isTrue();
        Instant due = TRIAL_END.minus(LEAD);
        assertThat(RewardEvaluator.evaluate(rule, ADA, 600_000, RewardWindow.closing(STARTED, 30, TRIAL_END, LEAD,
                due), false, false, due).qualifies()).isTrue();
        assertThat(rule.conditionLabel()).isEqualTo("$5,000 in sales in the first 30 days of the subscription");
    }

    @Test
    void theFreeDaysMoveTheTrialEndBeforeTheFirstInvoice() {
        Instant due = TRIAL_END.minus(LEAD);
        assertThat(FreeDays.newTrialEnd(SubscriptionStatus.TRIALING, TRIAL_END, TRIAL_END, due, 30))
                .as("the 2nd month is free: the first invoice moves 30 days")
                .isEqualTo(Instant.parse("2026-11-30T10:00:00Z"));
    }

    @Test
    void labelsAndValidation() {
        RewardRule rule = relief();
        assertThat(rule.checkedAtWindowEnd()).isTrue();
        assertThat(rule.conditionLabel())
                .isEqualTo("less than $1,000 in sales in the first 30 days of the subscription");
        assertThatThrownBy(() -> input(null, null, RewardMetric.GROSS_SALES).validate())
                .isInstanceOfSatisfying(SellerRuleViolation.class, e -> assertThat(e.getMessage())
                        .contains("withinDaysOfApproval"));
        assertThatThrownBy(() -> input(30, 7, RewardMetric.GROSS_SALES).validate())
                .isInstanceOfSatisfying(SellerRuleViolation.class, e -> assertThat(e.getMessage())
                        .contains("windowDays"));
        assertThatThrownBy(() -> input(30, null, RewardMetric.ACTIVE_LISTINGS).validate())
                .isInstanceOfSatisfying(SellerRuleViolation.class, e -> assertThat(e.getMessage())
                        .contains("metric"));
        RewardRuleInput ok = input(30, null, RewardMetric.GROSS_SALES);
        ok.validate();
        assertThat(ok.effectiveCheckAtWindowEnd()).isTrue();
        assertThat(ok.effectiveCheckLeadHours()).isEqualTo(24);
        RewardRuleInput legacy = new RewardRuleInput("Old", null, null, RewardMetric.GROSS_SALES, 100L, null, null,
                RewardType.CREDIT, null, 100L, null, null, List.of(), null, null, null, null);
        assertThat(legacy.effectiveComparison()).as("existing rules stay AT_LEAST")
                .isEqualTo(RewardComparison.AT_LEAST);
        assertThat(legacy.effectiveAnchor()).isEqualTo(RewardWindowAnchor.APPROVAL);
        assertThat(legacy.effectiveCheckAtWindowEnd()).isFalse();
    }

    private static RewardRuleInput input(Integer within, Integer windowDays, RewardMetric metric) {
        return new RewardRuleInput("Low-sales relief", null, OrgKind.SELLER, metric, 100_000L, windowDays, within,
                RewardComparison.LESS_THAN, RewardWindowAnchor.SUBSCRIPTION_START, null, null, RewardType.FREE_DAYS,
                30, null, null, null, List.of("seller_growth"), null, null, null, RewardRuleStatus.ACTIVE);
    }

    private static RewardRule relief() {
        return new RewardRule(UUID.randomUUID(), "Low-sales relief", null, RewardRuleStatus.ACTIVE, OrgKind.SELLER,
                RewardMetric.GROSS_SALES, 100_000, null, 30, RewardComparison.LESS_THAN,
                RewardWindowAnchor.SUBSCRIPTION_START, true, 24, RewardType.FREE_DAYS, 30, null, null, null,
                List.of("seller_growth"), null, null, null, 0, "staff", STARTED, "staff", STARTED, 0);
    }
}
