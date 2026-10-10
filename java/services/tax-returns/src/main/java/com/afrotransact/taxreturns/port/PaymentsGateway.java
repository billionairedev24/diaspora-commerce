package com.afrotransact.taxreturns.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.RefundLineAmount;

/** payments-service: the charged lines of an order and line-level refunds. Throws {@link UpstreamException}. */
public interface PaymentsGateway {

    Optional<ChargedPayment> paymentForOrder(UUID orderId);

    /** Refunds lines; the same Idempotency-Key replays the same refund. */
    IssuedRefund refund(UUID paymentId, List<RefundLineAmount> lines, String reason, String idempotencyKey);

    /** A payment and its lines as charged. */
    record ChargedPayment(UUID paymentId, String currency, List<ChargedLine> lines) {

        public ChargedPayment {
            lines = List.copyOf(lines);
        }
    }

    /** One charged line; {@code captured} is false until its shipment was captured. */
    record ChargedLine(String lineId, String sellerOrg, int quantity, long itemsMinor, long shippingMinor,
                       long taxMinor, boolean captured) {
    }

    /** payments-service's answer. */
    record IssuedRefund(String refundId, String status, long amountMinor) {
    }
}
