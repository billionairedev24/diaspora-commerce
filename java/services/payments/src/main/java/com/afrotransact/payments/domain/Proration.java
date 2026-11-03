package com.afrotransact.payments.domain;

import java.time.Duration;
import java.time.Instant;

/** What an upgrade in the middle of a paid period costs. */
public final class Proration {

    private Proration() {
    }

    /** The part of {@code priceMinor} that covers the time left in the period, rounded down. */
    public static long unused(long priceMinor, Instant periodStart, Instant periodEnd, Instant now) {
        long total = Duration.between(periodStart, periodEnd).toSeconds();
        long left = Math.max(0, Math.min(total, Duration.between(now, periodEnd).toSeconds()));
        return total <= 0 ? 0 : Math.multiplyExact(priceMinor, left) / total;
    }

    /**
     * Upgrading to a tier at the same interval keeps the period: the member pays the difference of the two prices for
     * the time left. Changing the interval starts a new period now at the new price, less what the old period had left.
     */
    public static long upgradeCharge(long oldPriceMinor, long newPriceMinor, boolean sameInterval, Instant periodStart,
                                     Instant periodEnd, Instant now) {
        if (sameInterval) {
            long total = Duration.between(periodStart, periodEnd).toSeconds();
            long left = Math.max(0, Math.min(total, Duration.between(now, periodEnd).toSeconds()));
            return total <= 0 ? 0 : Math.max(0, Math.multiplyExact(newPriceMinor - oldPriceMinor, left) / total);
        }
        return Math.max(0, newPriceMinor - unused(oldPriceMinor, periodStart, periodEnd, now));
    }
}
