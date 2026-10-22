package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One Stripe object whose movement on 1000 Stripe clearing differs from Stripe's balance transactions, tracked until
 * Finance explains it or corrects it with a ledger adjustment.
 *
 * @param ledgerMinor net movement on 1000 Stripe clearing in the ledger (debit positive)
 * @param stripeMinor Stripe's net for the same object
 */
public record ReconciliationItem(UUID id, UUID runId, Instant windowStart, String stripeRef, String currency,
                                 long ledgerMinor, long stripeMinor, ReconciliationStatus status, String note,
                                 String resolvedBy, Instant resolvedAt, Instant createdAt) {

    static final int MIN_NOTE = 10;

    public static ReconciliationItem open(UUID id, UUID runId, Instant windowStart, ReconciliationDrift drift,
                                          String currency, Instant now) {
        return new ReconciliationItem(id, runId, windowStart, drift.stripeRef(), currency, drift.ledgerMinor(),
                drift.stripeMinor(), ReconciliationStatus.OPEN, null, null, null, now);
    }

    /** Ledger minus Stripe: positive means the ledger holds more than Stripe says. */
    public long differenceMinor() {
        return ledgerMinor - stripeMinor;
    }

    public boolean resolved() {
        return status == ReconciliationStatus.EXPLAINED || status == ReconciliationStatus.ADJUSTED;
    }

    /** Finance's explanation, with nothing posted. */
    public ReconciliationItem explain(String actor, String explanation, Instant now) {
        if (status != ReconciliationStatus.OPEN) {
            throw new PaymentsRuleViolation("payments.reconciliation-not-open",
                    "Only an open difference can be marked explained");
        }
        if (explanation == null || explanation.trim().length() < MIN_NOTE) {
            throw new PaymentsRuleViolation("payments.reason-required",
                    "Write what explains the difference (at least " + MIN_NOTE + " characters)",
                    Map.of("note", "Required"));
        }
        return new ReconciliationItem(id, runId, windowStart, stripeRef, currency, ledgerMinor, stripeMinor,
                ReconciliationStatus.EXPLAINED, explanation.trim(), actor, now, createdAt);
    }

    /** A correcting journal for this item was posted. */
    public ReconciliationItem adjusted(String actor, Instant now) {
        if (status == ReconciliationStatus.SUPERSEDED) {
            throw new PaymentsRuleViolation("payments.reconciliation-superseded",
                    "A later reconciliation run replaced this difference; adjust the current one");
        }
        return new ReconciliationItem(id, runId, windowStart, stripeRef, currency, ledgerMinor, stripeMinor,
                ReconciliationStatus.ADJUSTED, note, actor, now, createdAt);
    }

    public ReconciliationItem superseded() {
        return new ReconciliationItem(id, runId, windowStart, stripeRef, currency, ledgerMinor, stripeMinor,
                ReconciliationStatus.SUPERSEDED, note, resolvedBy, resolvedAt, createdAt);
    }

    /** The same difference seen again by a later run of the same day keeps its explanation. */
    public ReconciliationItem carryOver(ReconciliationItem earlier) {
        if (earlier.status != ReconciliationStatus.EXPLAINED || earlier.ledgerMinor != ledgerMinor
                || earlier.stripeMinor != stripeMinor) {
            return this;
        }
        return new ReconciliationItem(id, runId, windowStart, stripeRef, currency, ledgerMinor, stripeMinor,
                ReconciliationStatus.EXPLAINED, earlier.note, earlier.resolvedBy, earlier.resolvedAt, createdAt);
    }
}
