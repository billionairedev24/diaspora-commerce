package com.afrotransact.seller.domain;

import java.time.Instant;

/**
 * One line of a Stripe invoice: the plan, a proration, or a metered usage line (S206).
 *
 * @param priceLookupKey     lookup key of the line's price when Stripe sent it (metered: {@code metered_*})
 * @param subscriptionItemId the subscription item the line bills, when Stripe sent it
 * @param meter              the usage meter of a metered line, null otherwise
 */
public record InvoiceLine(String description, long amountCents, Long quantity, String priceLookupKey,
                          String subscriptionItemId, UsageMeter meter, Instant periodStart, Instant periodEnd) {

    public InvoiceLine withMeter(UsageMeter m) {
        return new InvoiceLine(description, amountCents, quantity, priceLookupKey, subscriptionItemId, m,
                periodStart, periodEnd);
    }

    public boolean metered() {
        return meter != null;
    }

    /** The meter whose metered price has {@code lookupKey}, or null. */
    public static UsageMeter meterOf(String lookupKey) {
        if (lookupKey == null) {
            return null;
        }
        for (UsageMeter m : UsageMeter.values()) {
            if (m.priceLookupKey().equals(lookupKey)) {
                return m;
            }
        }
        return null;
    }
}
