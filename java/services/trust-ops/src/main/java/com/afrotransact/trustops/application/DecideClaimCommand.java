package com.afrotransact.trustops.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.trustops.domain.ClaimOutcome;
import com.afrotransact.trustops.port.PaymentsClient.RefundLine;

/**
 * Staff decision. A REFUND either names the payment and the line amounts (as before), or — with neither — is derived
 * from the order: payments refunds {@code refundAmountMinor} (default: the claimed amount) spread over the lines of
 * the claim's shipment (or the seller's lines on the order). The refund may never exceed the claimed amount.
 */
public record DecideClaimCommand(ClaimOutcome outcome, String note, UUID paymentId, List<RefundLine> refundLines,
                                 Long refundAmountMinor) {

    public DecideClaimCommand {
        refundLines = refundLines == null ? List.of() : List.copyOf(refundLines);
    }

    public DecideClaimCommand(ClaimOutcome outcome, String note, UUID paymentId, List<RefundLine> refundLines) {
        this(outcome, note, paymentId, refundLines, null);
    }

    /** True when staff named the payment and lines themselves. */
    public boolean explicitLines() {
        return paymentId != null || !refundLines.isEmpty();
    }
}
