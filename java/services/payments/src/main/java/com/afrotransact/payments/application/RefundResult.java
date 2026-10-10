package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.Refund;

/** Result of a refund, with the transfer reversals it caused. */
public record RefundResult(Refund refund, Payment payment, List<Reversal> reversals, boolean replayed) {

    public RefundResult {
        reversals = List.copyOf(reversals);
    }

    /** Part of a seller's transfer taken back because it was already transferred. */
    public record Reversal(String orgId, String stripeTransferId, String stripeReversalId, long amountMinor) {
    }
}
