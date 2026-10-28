package com.afrotransact.taxreturns.domain.tax;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * A filing and payment of one month's marketplace-facilitator sales tax to one jurisdiction.
 *
 * @param dueMinor net liability (collected − refunded) at the time it was recorded
 * @param paidMinor what was actually paid
 * @param voidedAt  set when the record was withdrawn as a mistake; the reason is kept
 */
public record TaxRemittance(UUID id, String jurisdiction, YearMonth month, String currency, long dueMinor,
                            long paidMinor, LocalDate filedOn, LocalDate paidOn, String confirmationRef,
                            String paymentRef, String note, String recordedBy, Instant recordedAt, Instant voidedAt,
                            String voidedBy, String voidReason) {

    public boolean active() {
        return voidedAt == null;
    }
}
