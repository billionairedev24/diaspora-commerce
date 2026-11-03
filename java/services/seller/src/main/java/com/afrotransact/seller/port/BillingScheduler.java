package com.afrotransact.seller.port;

import java.time.Instant;

import com.afrotransact.seller.domain.Cadence;

/**
 * Local billing scheduler: computes recurring period boundaries from an anchor date and a {@link Cadence}, replacing
 * the Stripe Billing subscription lifecycle. No external API is called; all scheduling is derived from RFC 5545
 * recurrence rules (ical4j).
 *
 * <p>RRULE mapping:
 * <ul>
 *   <li>MONTHLY  → {@code FREQ=MONTHLY;INTERVAL=1}
 *   <li>QUARTERLY → {@code FREQ=MONTHLY;INTERVAL=3}
 *   <li>ANNUAL   → {@code FREQ=YEARLY;INTERVAL=1}
 * </ul>
 */
public interface BillingScheduler {

    /**
     * The start of the period that contains {@code now}, anchored on {@code anchorDate} with the given cadence.
     * The anchor is the activation date (or trial end for the first paid period).
     */
    Instant periodStart(Instant anchorDate, Cadence cadence, Instant now);

    /**
     * The exclusive end of the current billing period (= start of the next one).
     * This is what the subscription's {@code currentPeriodEnd} should be set to.
     */
    Instant periodEnd(Instant anchorDate, Cadence cadence, Instant now);

    /**
     * The period end immediately after {@code after}: the next renewal date from {@code after}'s perspective.
     * Equivalent to advancing one recurrence occurrence from {@code after}.
     */
    Instant nextPeriodEnd(Instant anchorDate, Cadence cadence, Instant after);
}
