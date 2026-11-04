package com.afrotransact.seller.domain;

import java.time.Instant;

/**
 * One line of a plan invoice.
 *
 * @param kind     PLAN (the subscription for the period), USAGE (a metered add-on), COUPON (a discount, negative) or
 *                 PRORATION (an upgrade mid-period) or WAIVER (the plan fee a charge gate waived, negative)
 * @param quantity meter units for USAGE lines, else null
 * @param meter    the usage meter of a USAGE line, null otherwise
 */
public record InvoiceLine(String kind, String description, long amountCents, Long quantity, UsageMeter meter,
                          Instant periodStart, Instant periodEnd) {

    public static final String PLAN = "PLAN";
    public static final String USAGE = "USAGE";
    public static final String COUPON = "COUPON";
    public static final String PRORATION = "PRORATION";
    /** What a charge gate waived of the plan fee (negative), with the gate's reason. */
    public static final String WAIVER = "WAIVER";

    public boolean metered() {
        return meter != null;
    }
}
