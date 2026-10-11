package com.afrotransact.seller.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Free days are given by moving the Stripe subscription's {@code trial_end} (with {@code proration_behavior=none}):
 * no invoice is created until then, and the billing cycle restarts on that day. A trialing subscription's trial is
 * extended; an active one keeps the period it paid for and gets the free days after it, so the seller gets exactly N
 * free days whatever the cadence (a 100 %-off coupon would give a whole month, quarter or year instead).
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

    /**
     * A repeating coupon lasts whole months: N invoices of a cadence are N × its months (3 quarterly invoices = 9
     * months).
     */
    public static long couponMonths(Cadence cadence, int invoices) {
        return (long) cadence.months() * invoices;
    }
}
