package com.afrotransact.payments.application;

import java.util.List;

/**
 * Refund by order and line ids (service callers): payments finds the payment and spreads the amount.
 *
 * @param amountMinor total to refund; null = everything still refundable on the lines
 */
public record OrderRefundCommand(String orderId, List<String> lineIds, Long amountMinor, String reason) {

    public OrderRefundCommand {
        lineIds = lineIds == null ? List.of() : List.copyOf(lineIds);
    }
}
