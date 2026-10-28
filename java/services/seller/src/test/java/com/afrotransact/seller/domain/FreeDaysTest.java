package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class FreeDaysTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Test
    void freeMonthsAreCalendarMonthsAfterTheCurrentPeriod() {
        Instant periodEnd = Instant.parse("2026-11-30T12:00:00Z");
        assertThat(FreeDays.newTrialEndMonths(SubscriptionStatus.ACTIVE, null, periodEnd, NOW, 3))
                .as("3 months after Nov 30 is Feb 28, not 90 days").isEqualTo(Instant.parse("2027-02-28T12:00:00Z"));
    }

    @Test
    void aTrialIsExtendedFromItsEndAndAnExpiredOneFromNow() {
        Instant trialEnd = Instant.parse("2026-10-30T12:00:00Z");
        assertThat(FreeDays.newTrialEndMonths(SubscriptionStatus.TRIALING, trialEnd, trialEnd, NOW, 1))
                .isEqualTo(Instant.parse("2026-11-30T12:00:00Z"));
        assertThat(FreeDays.newTrialEndMonths(SubscriptionStatus.ACTIVE, null, null, NOW, 2))
                .isEqualTo(Instant.parse("2026-12-16T12:00:00Z"));
        assertThat(FreeDays.newTrialEnd(SubscriptionStatus.TRIALING, trialEnd, trialEnd, NOW, 10))
                .isEqualTo(Instant.parse("2026-11-09T12:00:00Z"));
    }

    @Test
    void freeTimeMovesTheSubscriptionIntoAFreeStretchAndRestartsTheCycleAfterIt() {
        Instant periodStart = Instant.parse("2026-10-01T12:00:00Z");
        Instant periodEnd = Instant.parse("2026-11-01T12:00:00Z");
        Subscription active = new Subscription("org_1", "seller_growth", 1, Cadence.MONTHLY, SubscriptionStatus.ACTIVE,
                null, periodStart, null, periodStart, periodEnd, false, null, null, null, DunningState.NONE, 0, null,
                NOW, 3);
        Instant until = Instant.parse("2027-02-01T12:00:00Z");
        Subscription free = active.withFreeUntil(until, NOW);
        assertThat(free.status()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(free.trialEndsAt()).isEqualTo(until);
        assertThat(free.currentPeriodEnd()).isEqualTo(until);
        assertThat(free.anchorDate()).as("the RRULE restarts when charging starts").isEqualTo(until);
        assertThat(free.renewalAt()).isEqualTo(until);
    }
}
