package com.afrotransact.payments.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.RecoveryResolution;
import com.afrotransact.payments.domain.TransferRecovery;
import com.afrotransact.payments.domain.TransferRecoveryStatus;

/**
 * Staff view and resolution of refunds Stripe would not take back from sellers' transfers. A recovery is cleared by the
 * seller's later transfers on its own; what Finance can do is close it earlier: the seller paid the platform back
 * outside Stripe, or the platform absorbs it. Both post to the ledger, so they need four eyes like any ledger
 * adjustment: one person proposes (ledger:adjust), a different one approves or rejects (ledger:approve).
 */
public interface TransferRecoveryService {

    /** Newest first; {@code status} and {@code orgId} null for all (payments:read). */
    List<TransferRecovery> list(TransferRecoveryStatus status, String orgId, int limit);

    TransferRecovery get(UUID id);

    /**
     * Proposes closing what is outstanding. {@code reference} is the bank reference of money received and is required
     * for {@link RecoveryResolution#RECOVERED}. Nothing is posted until a different person approves.
     */
    TransferRecovery propose(UUID id, RecoveryResolution resolution, String reason, String reference);

    /** Posts the journal (Dr 1050 or 5300 / Cr 2000:org) and closes the recovery; the approver is never the proposer. */
    TransferRecovery approve(UUID id, String note);

    /** Returns the recovery to plain OPEN, with the reason; the proposer may withdraw their own. */
    TransferRecovery reject(UUID id, String note);
}
