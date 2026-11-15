package com.afrotransact.payments.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What tax-returns asks payments for a return, over HTTP today ({@code GET /v1/payments?orderId=} and
 * {@code POST /v1/payments/{id}/refunds}), entered in-process under the calling module's principal. A caller without the
 * scope is refused with {@code ModuleApiException} status 403; failures carry the status the endpoint answers (409, 422).
 */
public interface ReturnRefundApi {

    /** The order's payment with its lines as charged and refunded so far; empty when the order has no payment (404). */
    Optional<ChargedPayment> paymentForOrder(UUID orderId);

    /** Refunds captured lines (partially); idempotent per key. */
    IssuedRefund refund(UUID paymentId, List<RefundAmount> lines, String reason, String idempotencyKey);

    record ChargedPayment(UUID paymentId, String currency, List<ChargedLine> lines) {

        public ChargedPayment {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** @param captured the line has been captured */
    record ChargedLine(String lineId, String sellerOrg, int quantity, long itemsMinor, long shippingMinor, long taxMinor,
                       boolean captured, long refundedItemsMinor, long refundedShippingMinor, long refundedTaxMinor) {
    }

    record RefundAmount(String lineId, long itemsMinor, long shippingMinor, long taxMinor) {
    }

    record IssuedRefund(String id, String status, long amountMinor) {
    }
}
