package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The membership state machine and the rules around tiers and benefits (S323), without a database. */
class MembershipTest {

    private static final Instant T0 = Instant.parse("2026-01-31T10:00:00Z");

    private static MembershipTier tier(String code, int sort, long monthly, long yearly, int trial,
                                       TierBenefit... benefits) {
        return new MembershipTier(code, code, "", TierStatus.PUBLISHED, sort, "USD", monthly, yearly, trial,
                List.of(benefits), T0, "test", T0, 0);
    }

    private static MembershipTier plus() {
        return tier("plus", 10, 999, 9900, 14, new TierBenefit(BenefitCode.FREE_DELIVERY, 2500L, null, null, null,
                null), new TierBenefit(BenefitCode.FREE_SHIPPING, 2500L, null, null, null, null));
    }

    // ------------------------------------------------------------------ periods and trials

    @Test
    void aTrialChargesNothingAndEndsOnTheTrialDay() {
        Membership m = Membership.trial(UUID.randomUUID(), "c1", plus(), BillingInterval.MONTHLY, "pm_1", T0);
        assertThat(m.status()).isEqualTo(MembershipStatus.TRIALING);
        assertThat(m.periodEnd()).isEqualTo(T0.plus(Duration.ofDays(14)));
        assertThat(m.trialEnd()).isEqualTo(m.periodEnd());
        assertThat(m.benefitsActive()).isTrue();
        assertThat(m.creditBenefitsActive()).as("credit benefits wait for the first paid period").isFalse();
        assertThat(m.dueAt(T0.plus(Duration.ofDays(13)))).isFalse();
        assertThat(m.dueAt(T0.plus(Duration.ofDays(14)))).isTrue();
    }

    @Test
    void monthlyPeriodsFollowTheCalendarAndYearlyOnesTheYear() {
        assertThat(BillingInterval.MONTHLY.periodEnd(T0)).isEqualTo(Instant.parse("2026-02-28T10:00:00Z"));
        assertThat(BillingInterval.YEARLY.periodEnd(T0)).isEqualTo(Instant.parse("2027-01-31T10:00:00Z"));
    }

    @Test
    void aRenewalStartsWhereThePaidPeriodEndedAtTheChargedPrice() {
        Membership m = Membership.paid(UUID.randomUUID(), "c1", plus(), BillingInterval.MONTHLY, "pm_1", T0);
        Membership renewed = m.renewed("plus", BillingInterval.MONTHLY, 999, m.periodEnd());
        assertThat(renewed.periodStart()).isEqualTo(m.periodEnd());
        assertThat(renewed.periodEnd()).isEqualTo(BillingInterval.MONTHLY.periodEnd(m.periodEnd()));
        assertThat(renewed.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(renewed.priceMinor()).isEqualTo(999);
    }

    // ------------------------------------------------------------------ failed renewals

    @Test
    void failedRenewalsRetryOnTheStaffScheduleThenLapse() {
        List<Integer> retryDays = List.of(1, 3, 5);
        Membership m = Membership.paid(UUID.randomUUID(), "c1", plus(), BillingInterval.MONTHLY, "pm_1", T0);
        Instant due = m.periodEnd();

        Membership first = m.renewalFailed(retryDays, due);
        assertThat(first.status()).isEqualTo(MembershipStatus.PAST_DUE);
        assertThat(first.retryCount()).isEqualTo(1);
        assertThat(first.nextRetryAt()).isEqualTo(due.plus(Duration.ofDays(1)));
        assertThat(first.benefitsActive()).as("the grace period keeps the benefits").isTrue();
        assertThat(first.dueAt(due.plus(Duration.ofHours(12)))).isFalse();
        assertThat(first.dueAt(due.plus(Duration.ofDays(1)))).isTrue();

        Membership second = first.renewalFailed(retryDays, due.plus(Duration.ofDays(1)));
        assertThat(second.nextRetryAt()).isEqualTo(due.plus(Duration.ofDays(1)).plus(Duration.ofDays(3)));
        Membership third = second.renewalFailed(retryDays, due.plus(Duration.ofDays(4)));
        assertThat(third.status()).isEqualTo(MembershipStatus.PAST_DUE);

        Membership last = third.renewalFailed(retryDays, due.plus(Duration.ofDays(9)));
        assertThat(last.status()).isEqualTo(MembershipStatus.LAPSED);
        assertThat(last.endReason()).isEqualTo(Membership.ENDED_PAYMENT_FAILED);
        assertThat(last.live()).isFalse();
        assertThat(last.benefitsActive()).isFalse();
    }

    @Test
    void withoutRetriesTheFirstFailureLapses() {
        Membership m = Membership.paid(UUID.randomUUID(), "c1", plus(), BillingInterval.MONTHLY, "pm_1", T0);
        assertThat(m.renewalFailed(List.of(), m.periodEnd()).status()).isEqualTo(MembershipStatus.LAPSED);
    }

    // ------------------------------------------------------------------ cancel, change

    @Test
    void cancelingKeepsTheBenefitsToThePeriodEndAndResumingTakesItBack() {
        Membership m = Membership.paid(UUID.randomUUID(), "c1", plus(), BillingInterval.MONTHLY, "pm_1", T0);
        Membership canceled = m.canceled(T0.plusSeconds(60));
        assertThat(canceled.cancelAtPeriodEnd()).isTrue();
        assertThat(canceled.benefitsActive()).isTrue();
        assertThat(canceled.periodEnd()).isEqualTo(m.periodEnd());
        assertThat(canceled.resumed(T0.plusSeconds(120)).cancelAtPeriodEnd()).isFalse();
    }

    @Test
    void aScheduledChangeWaitsForThePeriodEndAndCancelingDropsIt() {
        Membership m = Membership.paid(UUID.randomUUID(), "c1", plus(), BillingInterval.MONTHLY, "pm_1", T0);
        Membership scheduled = m.changeScheduled("basic", BillingInterval.YEARLY, T0);
        assertThat(scheduled.pendingTierCode()).isEqualTo("basic");
        assertThat(scheduled.tierCode()).as("the current tier stays until the period ends").isEqualTo("plus");
        assertThat(scheduled.canceled(T0).pendingTierCode()).isNull();
        assertThat(scheduled.changeCleared(T0).pendingTierCode()).isNull();
    }

    @Test
    void anUpgradeInThePeriodKeepsTheDatesAndOneWithANewIntervalStartsANewPeriod() {
        MembershipTier premium = tier("premium", 20, 1999, 19900, 14, TierBenefit.flag(BenefitCode.PRIORITY_SUPPORT));
        Membership m = Membership.paid(UUID.randomUUID(), "c1", plus(), BillingInterval.MONTHLY, "pm_1", T0);
        Instant mid = T0.plus(Duration.ofDays(10));
        Membership same = m.upgradedInPeriod(premium, mid);
        assertThat(same.tierCode()).isEqualTo("premium");
        assertThat(same.periodEnd()).isEqualTo(m.periodEnd());
        assertThat(same.priceMinor()).isEqualTo(1999);
        Membership yearly = m.upgradedNewPeriod(premium, BillingInterval.YEARLY, mid);
        assertThat(yearly.periodStart()).isEqualTo(mid);
        assertThat(yearly.periodEnd()).isEqualTo(BillingInterval.YEARLY.periodEnd(mid));
        assertThat(yearly.priceMinor()).isEqualTo(19900);
    }

    // ------------------------------------------------------------------ complimentary days

    @Test
    void complimentaryDaysCostNothingAndFreeDaysExtendAPaidPeriod() {
        Membership free = Membership.complimentary(UUID.randomUUID(), "c1", plus(), 30, T0);
        assertThat(free.paidByCard()).isFalse();
        assertThat(free.periodEnd()).isEqualTo(T0.plus(Duration.ofDays(30)));
        assertThat(free.creditBenefitsActive()).isTrue();

        Membership paid = Membership.paid(UUID.randomUUID(), "c1", plus(), BillingInterval.MONTHLY, "pm_1", T0);
        Membership extended = paid.extended(10, T0);
        assertThat(extended.periodEnd()).isEqualTo(paid.periodEnd().plus(Duration.ofDays(10)));
        assertThat(extended.freeDaysGranted()).isEqualTo(10);

        Membership converted = free.convertedToPaid(plus(), BillingInterval.YEARLY, "pm_2", T0);
        assertThat(converted.paidByCard()).isTrue();
        assertThat(converted.periodEnd()).as("nothing is charged until the free days are over")
                .isEqualTo(free.periodEnd());
    }

    // ------------------------------------------------------------------ tiers and benefits

    @Test
    void aTierNeedsSanePricesAndBenefitsWithTheirOwnNumbers() {
        assertThatThrownBy(() -> tier("bad", 1, 999, 99_999, 0, TierBenefit.flag(BenefitCode.FREE_RETURNS)))
                .isInstanceOf(PaymentsRuleViolation.class).satisfies(e -> assertThat(
                        ((PaymentsRuleViolation) e).fieldErrors()).containsKey("yearlyPriceMinor"));
        assertThatThrownBy(() -> new TierBenefit(BenefitCode.CASHBACK, null, null, null, null, null))
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> new TierBenefit(BenefitCode.FREE_RETURNS, null, null, 100, null, null))
                .as("a flag benefit takes no numbers").isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> new TierBenefit(BenefitCode.EARLY_ACCESS, null, null, null, 0, null))
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> tier("empty", 1, 999, 9900, 0)).as("a published tier has a benefit")
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> tier("Bad Code", 1, 999, 9900, 0, TierBenefit.flag(BenefitCode.FREE_RETURNS)))
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThat(plus().price(BillingInterval.YEARLY).minor()).isEqualTo(9900);
    }

    @Test
    void settingsKeepTheRetryScheduleShort() {
        assertThatThrownBy(() -> new MembershipSettings(7, List.of(1, 2, 3, 4, 5, 6, 7), "x", T0, 0))
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> new MembershipSettings(90, List.of(1), "x", T0, 0))
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThat(new MembershipSettings(7, List.of(1, 3, 5), "x", T0, 0).retryDays()).containsExactly(1, 3, 5);
    }
}
