package com.afrotransact.seller.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;

/**
 * Metered usage of one organisation on one UTC day (S206), and what Stripe Billing has received for it.
 *
 * @param quantity         usage in meter units, as last read from the source service
 * @param reportedQuantity sum of the meter events sent to Stripe for this day
 * @param revision         meter events sent so far; the next identifier uses {@code revision + 1}
 * @param lastError        why the last report did not go through (null when fine)
 */
public record UsageDay(String orgId, UsageMeter meter, LocalDate day, long quantity, long reportedQuantity,
                       int revision, Instant reportedAt, String lastError, Instant updatedAt) {

    /** Stripe only accepts meter events whose timestamp is within the past 35 days. */
    public static final int STRIPE_MAX_AGE_DAYS = 35;

    public UsageDay {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(meter, "meter");
        Objects.requireNonNull(day, "day");
    }

    /** Units still to report (negative when usage went down after it was reported). */
    public long delta() {
        return quantity - reportedQuantity;
    }

    /** Usage dropped below what was already billed: needs a credit, never a negative meter event. */
    public boolean needsCorrection() {
        return quantity < reportedQuantity;
    }

    /** Identifier of the next meter event; stable across retries so Stripe de-duplicates a repeated send. */
    public String nextIdentifier() {
        return orgId + ":" + meter.name().toLowerCase(Locale.ROOT) + ":" + day + ":r" + (revision + 1);
    }

    /** Event timestamp: noon UTC of the day, never in the future. */
    public Instant eventTimestamp(Instant now) {
        Instant noon = day.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC);
        return noon.isAfter(now) ? now : noon;
    }

    /** Whether Stripe still accepts a meter event for this day. */
    public boolean reportable(Instant now) {
        return !day.isBefore(LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(STRIPE_MAX_AGE_DAYS - 1));
    }
}
