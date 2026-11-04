package com.afrotransact.seller.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * Metered usage of one organisation on one UTC day (S206), and how much of it plan invoices have billed.
 *
 * @param quantity         usage in meter units, as last read from the source service
 * @param reportedQuantity quantity billed on plan invoices so far
 * @param revision         times this day was billed; the next identifier uses {@code revision + 1}
 * @param lastError        why the last report did not go through (null when fine)
 */
public record UsageDay(String orgId, UsageMeter meter, LocalDate day, long quantity, long reportedQuantity,
                       int revision, Instant reportedAt, String lastError, Instant updatedAt) {

    public UsageDay {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(meter, "meter");
        Objects.requireNonNull(day, "day");
    }

    /** Units still to invoice (negative when usage went down after it was reported). */
    public long delta() {
        return quantity - reportedQuantity;
    }

    /** Usage dropped below what was already billed: needs a credit, never a negative charge. */
    public boolean needsCorrection() {
        return quantity < reportedQuantity;
    }

    /** Identifier of the next billing entry; stable across retries so a repeated run is a no-op. */
    public String nextIdentifier() {
        return orgId + ":" + meter.name().toLowerCase(Locale.ROOT) + ":" + day + ":r" + (revision + 1);
    }
}
