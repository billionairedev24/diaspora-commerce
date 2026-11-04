package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.Refund;
import com.afrotransact.payments.domain.TransferRecovery;

/**
 * Result of a refund, with the transfer reversals it caused and the ones Stripe refused ({@code recoveries}: what the
 * sellers owe the platform).
 */
public record RefundResult(Refund refund, Payment payment, List<Reversal> reversals,
                           List<TransferRecovery> recoveries, boolean replayed) {

    public RefundResult {
        reversals = List.copyOf(reversals);
        recoveries = List.copyOf(recoveries);
    }

    /** Part of a seller's transfer taken back because it was already transferred. */
    public record Reversal(String orgId, String stripeTransferId, String stripeReversalId, long amountMinor) {
    }
}
