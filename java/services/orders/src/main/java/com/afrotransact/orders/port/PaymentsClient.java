package com.afrotransact.orders.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** payments-service (SERVICE principal with payments:service). */
public interface PaymentsClient {

    /** {@code POST /v1/payments}: PaymentIntent with manual capture; Idempotency-Key = order id. */
    Intent createIntent(NewIntent request);

    /** {@code GET /v1/payments/{id}?refresh=…}. */
    Intent get(String paymentId, boolean refresh);

    /** {@code GET /v1/payments?orderId=}: the payment of an order, if one was created. */
    Optional<Intent> findByOrder(String orderId);

    /** {@code POST /v1/payments/{id}/cancel}: releases the authorisation. */
    void cancel(String paymentId, String reason);

    /** {@code POST /v1/payments/{id}/capture} for one shipment; idempotent per shipment. */
    Capture capture(String paymentId, CaptureRequest request);

    /** {@code POST /v1/payments/{id}/refunds}: line-level refund of a captured shipment. */
    String refund(String paymentId, String idempotencyKey, List<RefundLine> lines, String reason);

    /** {@code POST /v1/settlements/{orderId}/delivered}: starts the settlement hold. Idempotent. */
    void delivered(String orderId, Instant deliveredAt);

    /**
     * {@code POST /v1/settlements/{orderId}/shipments/{shipmentId}/delivered}: starts the hold of one shipment so its
     * seller net is transferred without waiting for the order's other shipments. Idempotent.
     */
    void shipmentDelivered(String orderId, String shipmentId, Instant deliveredAt);

    /** {@code GET /v1/orders/{orderId}/split}. */
    Split split(String orderId);

    /** Intent to create. */
    record NewIntent(String orderId, long amountMinor, String currency, Instant placedAt,
                     Map<String, String> metadata, List<Line> lines) {
    }

    /**
     * One payment line: fulfilment FBA (AfroTransact-fulfilled) or SELF (seller-fulfilled).
     *
     * @param shippingTaxMinor part of taxMinor charged on shipping (payments keeps it apart)
     */
    record Line(String lineId, String sellerOrg, String category, String fulfilment, int quantity, long itemsMinor,
                long shippingMinor, long taxMinor, String taxJurisdiction, long shippingTaxMinor) {

        public Line(String lineId, String sellerOrg, String category, String fulfilment, int quantity,
                    long itemsMinor, long shippingMinor, long taxMinor, String taxJurisdiction) {
            this(lineId, sellerOrg, category, fulfilment, quantity, itemsMinor, shippingMinor, taxMinor,
                    taxJurisdiction, 0);
        }
    }

    /** Payment as returned by payments (status AUTHORIZED once the shopper confirmed). */
    record Intent(String paymentId, String status, String clientSecret, long amountMinor, String failureMessage) {
    }

    /** Capture of one shipment. */
    record CaptureRequest(String shipmentId, List<String> lineIds, long amountMinor, boolean finalCapture) {
    }

    /** Capture result. */
    record Capture(String captureId, long amountMinor) {
    }

    /** Refund of one line. */
    record RefundLine(String lineId, long itemsMinor, long shippingMinor, long taxMinor) {
    }

    /** Payment split of an order (one charge, many recipients). */
    record Split(String orderId, long chargeMinor, long stripeFeeMinor, long taxCollectedMinor, String currency,
                 List<Recipient> recipients) {
    }

    /** One recipient of the split. */
    record Recipient(String recipient, String kind, long itemsMinor, long shippingMinor, long taxMinor,
                     long commissionMinor, long fbaFeesMinor, long stripeFeeMinor, long netMinor) {
    }
}
