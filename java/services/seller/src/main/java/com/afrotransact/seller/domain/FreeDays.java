package com.afrotransact.seller.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Free days (or calendar months) are given by moving the first-charge date out: no invoice is issued until then, and
 * the billing cycle restarts on that day (the subscription's RRULE anchor moves with it). A trialing subscription's
 * trial is extended; an active one keeps the period it paid for and gets the free time after it, so the seller gets
 * exactly N free days or months whatever the cadence (a 100 %-off coupon would give a whole month, quarter or year
 * instead).
 */
public final class FreeDays {

    private FreeDays() {
    }

    public static Instant newTrialEnd(SubscriptionStatus status, Instant trialEnd, Instant periodEnd, Instant now,
                                      int days) {
        Duration extra = Duration.ofDays(days);
        if (status == SubscriptionStatus.TRIALING && trialEnd != null && trialEnd.isAfter(now)) {
            return trialEnd.plus(extra);
        }
        if (periodEnd != null && periodEnd.isAfter(now)) {
            return periodEnd.plus(extra);
        }
        return now.plus(extra);
    }

    /** Like {@link #newTrialEnd} for calendar months: 3 months after Nov 30 end on Feb 28 (no drift to 90 days). */
    public static Instant newTrialEndMonths(SubscriptionStatus status, Instant trialEnd, Instant periodEnd,
                                            Instant now, int months) {
        Instant base = now;
        if (status == SubscriptionStatus.TRIALING && trialEnd != null && trialEnd.isAfter(now)) {
            base = trialEnd;
        } else if (periodEnd != null && periodEnd.isAfter(now)) {
            base = periodEnd;
        }
        return base.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
    }

    /**
     * A repeating coupon lasts whole months: N invoices of a cadence are N × its months (3 quarterly invoices = 9
     * months).
     */
    public static long couponMonths(Cadence cadence, int invoices) {
        return (long) cadence.months() * invoices;
    }
}
