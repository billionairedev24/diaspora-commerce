package com.afrotransact.payments.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.AdjustmentStatus;
import com.afrotransact.payments.domain.EntrySide;
import com.afrotransact.payments.domain.LedgerAdjustment;

/**
 * Correcting journals with four eyes: one Finance person proposes, a different one approves, and only then does the
 * journal reach the ledger.
 */
public interface LedgerAdjustmentService {

    /** @param itemId the reconciliation difference this corrects, or null */
    LedgerAdjustment propose(ProposeAdjustment command);

    /** Posts the journal; the approver is never the proposer (ledger:approve). */
    LedgerAdjustment approve(UUID id, String note);

    /** Rejects it, with the reason; the proposer may withdraw their own (ledger:approve). */
    LedgerAdjustment reject(UUID id, String note);

    /** Newest first; {@code status} null for all (payments:read). */
    List<LedgerAdjustment> list(AdjustmentStatus status, int limit);

    LedgerAdjustment get(UUID id);

    /**
     * @param stripeRef the Stripe object the correction is booked against; taken from the item when there is one
     * @param lines     debit and credit lines; they must balance
     */
    record ProposeAdjustment(UUID itemId, String stripeRef, String reason, List<Line> lines) {
    }

    /** One line: account code (e.g. "1000", "2000"), its key for scoped accounts (the org id), side and amount. */
    record Line(String account, String key, EntrySide side, long amountMinor, String orgId) {
    }
}
