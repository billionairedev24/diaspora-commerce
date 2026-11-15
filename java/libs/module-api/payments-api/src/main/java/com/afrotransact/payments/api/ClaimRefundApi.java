package com.afrotransact.payments.api;

import java.util.List;
import java.util.UUID;

/**
 * What the trust-ops module asks payments in-process when it settles a claim: refund by payment and line amounts
 * ({@code POST /v1/payments/{id}/refunds}) or by order and line ids ({@code POST /v1/orders/{orderId}/refunds}; the amount is
 * spread over the lines). Scope {@code payments:service}, entered under the caller's in-process principal; a caller without
 * it is refused with {@code com.afrotransact.module.ModuleApiException} status 403. Both are idempotent per key. Refusals keep
 * the endpoint's status and code (409 {@code payments.already-refunded}-style conflicts, 422 rule violations).
 */
public interface ClaimRefundApi {

    ClaimRefund refund(UUID paymentId, List<RefundAmount> lines, String reason, String idempotencyKey);

    /** @param amountMinor total to refund; zero or negative is not meaningful here, callers pass the claim amount */
    ClaimRefund refundByOrder(String orderId, List<String> lineIds, long amountMinor, String reason, String idempotencyKey);

    record RefundAmount(String lineId, long itemsMinor, long shippingMinor, long taxMinor) {
    }

    /** @param replayed true when this key already refunded and the original result is returned */
    record ClaimRefund(String refundId, long amountMinor, String status, boolean replayed) {
    }
}
