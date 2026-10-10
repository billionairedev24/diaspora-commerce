package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of comparing the ledger's 1000 Stripe clearing with Stripe balance transactions over a window.
 *
 * @param driftMinor Σ |ledger − Stripe| over every mismatched source (0 = reconciled)
 */
public record ReconciliationReport(UUID id, Instant windowStart, Instant windowEnd, String currency,
                                   long ledgerNetMinor, long stripeNetMinor, long driftMinor,
                                   List<ReconciliationDrift> drifts, Instant createdAt) {

    public ReconciliationReport {
        drifts = List.copyOf(drifts);
    }
}
