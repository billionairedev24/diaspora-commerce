package com.afrotransact.seller.adapter.out.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.afrotransact.seller.domain.Cadence;
import org.junit.jupiter.api.Test;

/** Billing periods are RFC 5545 recurrences from the subscription's anchor. */
class RruleBillingSchedulerTest {

    private final RruleBillingScheduler scheduler = new RruleBillingScheduler();

    @Test
    void monthlyQuarterlyAndYearlyPeriodsFollowTheAnchor() {
        Instant anchor = Instant.parse("2026-01-15T09:30:00Z");
        assertThat(scheduler.nextPeriodEnd(anchor, Cadence.MONTHLY, anchor))
                .isEqualTo(Instant.parse("2026-02-15T09:30:00Z"));
        assertThat(scheduler.nextPeriodEnd(anchor, Cadence.QUARTERLY, anchor))
                .isEqualTo(Instant.parse("2026-04-15T09:30:00Z"));
        assertThat(scheduler.nextPeriodEnd(anchor, Cadence.ANNUAL, anchor))
                .isEqualTo(Instant.parse("2027-01-15T09:30:00Z"));
    }

    @Test
    void theCurrentPeriodContainsNow() {
        Instant anchor = Instant.parse("2026-01-15T09:30:00Z");
        Instant now = Instant.parse("2026-04-20T00:00:00Z");
        assertThat(scheduler.periodStart(anchor, Cadence.MONTHLY, now))
                .isEqualTo(Instant.parse("2026-04-15T09:30:00Z"));
        assertThat(scheduler.periodEnd(anchor, Cadence.MONTHLY, now))
                .isEqualTo(Instant.parse("2026-05-15T09:30:00Z"));
        assertThat(scheduler.periodStart(anchor, Cadence.QUARTERLY, now))
                .isEqualTo(Instant.parse("2026-04-15T09:30:00Z"));
    }

    @Test
    void anchorsOnTheEndOfAMonthFallBackToItsLastValidDayAndReturnToTheAnchor() {
        Instant anchor = Instant.parse("2027-01-31T08:00:00Z");
        Instant feb = scheduler.nextPeriodEnd(anchor, Cadence.MONTHLY, anchor);
        assertThat(feb).as("February has no 31st: the 28th, not March 31").isEqualTo(Instant.parse("2027-02-28T08:00:00Z"));
        Instant mar = scheduler.nextPeriodEnd(anchor, Cadence.MONTHLY, feb);
        assertThat(mar).isEqualTo(Instant.parse("2027-03-31T08:00:00Z"));
        assertThat(scheduler.nextPeriodEnd(anchor, Cadence.MONTHLY, mar))
                .isEqualTo(Instant.parse("2027-04-30T08:00:00Z"));
        assertThat(scheduler.nextPeriodEnd(Instant.parse("2028-01-31T08:00:00Z"), Cadence.MONTHLY,
                Instant.parse("2028-01-31T08:00:00Z"))).as("leap year").isEqualTo(Instant.parse("2028-02-29T08:00:00Z"));
    }

    @Test
    void quarterlyAndYearlyAnchorsOnTheEndOfAMonthStayOnTheirCalendar() {
        Instant nov30 = Instant.parse("2026-11-30T10:00:00Z");
        Instant feb = scheduler.nextPeriodEnd(nov30, Cadence.QUARTERLY, nov30);
        assertThat(feb).isEqualTo(Instant.parse("2027-02-28T10:00:00Z"));
        assertThat(scheduler.nextPeriodEnd(nov30, Cadence.QUARTERLY, feb))
                .isEqualTo(Instant.parse("2027-05-30T10:00:00Z"));
        Instant leap = Instant.parse("2028-02-29T10:00:00Z");
        Instant next = scheduler.nextPeriodEnd(leap, Cadence.ANNUAL, leap);
        assertThat(next).as("a leap-day anchor renews on Feb 28 in common years")
                .isEqualTo(Instant.parse("2029-02-28T10:00:00Z"));
        assertThat(scheduler.nextPeriodEnd(leap, Cadence.ANNUAL, Instant.parse("2031-06-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2032-02-29T10:00:00Z"));
    }
}
