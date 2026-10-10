package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.RefundFault;

/**
 * Refund by order and line ids (service callers): payments finds the payment and spreads the amount.
 *
 * @param amountMinor total to refund; null = everything still refundable on the lines
 * @param fault       who caused it (AfroTransact Delivery lines, S255); null = SELLER
 */
public record OrderRefundCommand(String orderId, List<String> lineIds, Long amountMinor, String reason,
                                 RefundFault fault) {

    public OrderRefundCommand {
        lineIds = lineIds == null ? List.of() : List.copyOf(lineIds);
    }

    /** A seller-fault refund. */
    public OrderRefundCommand(String orderId, List<String> lineIds, Long amountMinor, String reason) {
        this(orderId, lineIds, amountMinor, reason, null);
    }
}
