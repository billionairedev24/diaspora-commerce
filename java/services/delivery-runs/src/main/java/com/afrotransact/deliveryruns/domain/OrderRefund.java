package com.afrotransact.deliveryruns.domain;

import java.time.Instant;

/**
 * Refund progress of one cancelled pre-order.
 *
 * @param status      where it stands
 * @param amountMinor amount refunded or released (null while pending)
 * @param updatedAt   last change
 * @param error       why it failed (FAILED only)
 */
public record OrderRefund(RefundStatus status, Long amountMinor, Instant updatedAt, String error) {

    public OrderRefund {
        if (status == null || updatedAt == null) {
            throw new IllegalArgumentException("status and time are required");
        }
    }
}
