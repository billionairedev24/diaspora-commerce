package com.afrotransact.trustops.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.trustops.domain.ClaimOutcome;
import com.afrotransact.trustops.port.PaymentsClient.RefundLine;

/**
 * Staff decision. A REFUND needs the order's payment id and the lines to refund (payments refunds by line); the sum
 * of the lines may not exceed the claimed amount.
 */
public record DecideClaimCommand(ClaimOutcome outcome, String note, UUID paymentId, List<RefundLine> refundLines) {

    public DecideClaimCommand {
        refundLines = refundLines == null ? List.of() : List.copyOf(refundLines);
    }
}
