package com.afrotransact.payments.domain;

import java.time.Instant;
import java.time.ZoneOffset;

/** How often a member is billed. */
public enum BillingInterval {
    MONTHLY,
    YEARLY;

    /** The end of a period that starts at {@code from} (calendar months or years, UTC). */
    public Instant periodEnd(Instant from) {
        return switch (this) {
            case MONTHLY -> from.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
            case YEARLY -> from.atZone(ZoneOffset.UTC).plusYears(1).toInstant();
        };
    }
}
