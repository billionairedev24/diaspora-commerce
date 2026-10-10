package com.afrotransact.payments.application;

/**
 * Refunds addressed by order and line ids, for services that know the order but not the payment (trust-ops claims,
 * returns). The amount is spread over the lines' remaining items, shipping and tax ({@code RefundAllocator}) and
 * refunded through the normal line refund (ledger, commission and transfer reversals included).
 */
public interface OrderRefundService {

    /** Idempotent per Idempotency-Key: a retry returns the first refund even though the remainders moved since. */
    RefundResult refundByOrder(OrderRefundCommand command, String idempotencyKey);
}
