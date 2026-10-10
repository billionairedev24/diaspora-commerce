package com.afrotransact.trustops.port;

import java.util.List;
import java.util.UUID;

/** payments-service {@code POST /v1/payments/{id}/refunds} (payments:service, Idempotency-Key required). */
public interface PaymentsClient {

    RefundResult refund(UUID paymentId, List<RefundLine> lines, String reason, String idempotencyKey);

    /** One refunded order line (amounts in minor units). */
    record RefundLine(String lineId, long itemsMinor, long shippingMinor, long taxMinor) {

        public long total() {
            return itemsMinor + shippingMinor + taxMinor;
        }
    }

    /** payments' RefundResponse: id, amount, status and whether the idempotent call was replayed. */
    record RefundResult(String refundId, long amountMinor, String status, boolean replayed) {
    }
}
