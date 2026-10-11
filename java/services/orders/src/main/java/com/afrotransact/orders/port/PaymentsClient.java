package com.afrotransact.orders.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** payments-service (SERVICE principal with payments:service). */
public interface PaymentsClient {

    /**
     * {@code POST /v1/payments}: PaymentIntent with manual capture; Idempotency-Key = order id. S272 late adds:
     * {@code offSession} confirms it now on the customer's default saved card, {@code paymentLinkHours} creates a
     * hosted payment link instead.
     */
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

    /**
     * {@code POST /v1/delivery-pricing/quote} (S255): AfroTransact Delivery customer charge and seller fee for a trip
     * of {@code distanceMiles} from the seller's pickup location, under the fee schedule in force now.
     */
    DeliveryQuote quoteDelivery(String sellerOrgId, double distanceMiles, int packages);

    /**
     * A delivery quote.
     *
     * @param unavailableReason NOT_CONFIGURED or BEYOND_MAX_RADIUS when not available
     */
    record DeliveryQuote(boolean available, String unavailableReason, long customerChargeMinor, long sellerFeeMinor,
                         int scheduleVersion) {
    }

    /**
     * Intent to create.
     *
     * @param shopperId the customer's AfroTransact id (token subject): payments puts the shopper's Stripe Customer on
     *                  the PaymentIntent so the mobile PaymentSheet lists their saved cards (S245); null for none
     */
    record NewIntent(String orderId, long amountMinor, String currency, Instant placedAt,
                     Map<String, String> metadata, List<Line> lines, String shopperId,
                     List<String> paymentMethodTypes, boolean offSession, Integer paymentLinkHours,
                     boolean saveForOffSession) {

        public NewIntent {
            paymentMethodTypes = paymentMethodTypes == null ? List.of() : List.copyOf(paymentMethodTypes);
        }

        /** An intent that does not save the payment method. */
        public NewIntent(String orderId, long amountMinor, String currency, Instant placedAt,
                         Map<String, String> metadata, List<Line> lines, String shopperId,
                         List<String> paymentMethodTypes, boolean offSession, Integer paymentLinkHours) {
            this(orderId, amountMinor, currency, placedAt, metadata, lines, shopperId, paymentMethodTypes, offSession,
                    paymentLinkHours, false);
        }

        public NewIntent(String orderId, long amountMinor, String currency, Instant placedAt,
                         Map<String, String> metadata, List<Line> lines, String shopperId) {
            this(orderId, amountMinor, currency, placedAt, metadata, lines, shopperId, List.of(), false, null);
        }

        public NewIntent(String orderId, long amountMinor, String currency, Instant placedAt,
                         Map<String, String> metadata, List<Line> lines) {
            this(orderId, amountMinor, currency, placedAt, metadata, lines, null);
        }
    }

    /**
     * One payment line: fulfilment FBA (AfroTransact-fulfilled), SELF (seller-fulfilled) or AFROTRANSACT_DELIVERY
     * (seller-packed, AfroTransact driver from the seller's store, S255).
     *
     * @param shippingTaxMinor part of taxMinor charged on shipping (payments keeps it apart)
     * @param deliveryFeeMinor AFROTRANSACT_DELIVERY: the seller's pickup-delivery fee allocated to this line
     */
    record Line(String lineId, String sellerOrg, String category, String fulfilment, int quantity, long itemsMinor,
                long shippingMinor, long taxMinor, String taxJurisdiction, long shippingTaxMinor,
                long deliveryFeeMinor) {

        public Line(String lineId, String sellerOrg, String category, String fulfilment, int quantity,
                    long itemsMinor, long shippingMinor, long taxMinor, String taxJurisdiction,
                    long shippingTaxMinor) {
            this(lineId, sellerOrg, category, fulfilment, quantity, itemsMinor, shippingMinor, taxMinor,
                    taxJurisdiction, shippingTaxMinor, 0);
        }

        public Line(String lineId, String sellerOrg, String category, String fulfilment, int quantity,
                    long itemsMinor, long shippingMinor, long taxMinor, String taxJurisdiction) {
            this(lineId, sellerOrg, category, fulfilment, quantity, itemsMinor, shippingMinor, taxMinor,
                    taxJurisdiction, 0);
        }
    }

    /**
     * Payment as returned by payments (status AUTHORIZED once the shopper confirmed).
     *
     * @param paymentLinkUrl       hosted payment link (created with {@code paymentLinkHours}); null otherwise
     * @param paymentLinkExpiresAt when the link expires
     */
    record Intent(String paymentId, String status, String clientSecret, long amountMinor, String failureMessage,
                  String paymentLinkUrl, Instant paymentLinkExpiresAt) {

        public Intent(String paymentId, String status, String clientSecret, long amountMinor,
                      String failureMessage) {
            this(paymentId, status, clientSecret, amountMinor, failureMessage, null, null);
        }
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

    /**
     * One recipient of the split.
     *
     * @param deliveryFeesMinor AfroTransact Delivery pickup fees (S255/S259): what the seller paid for the trips of its
     *                          AFROTRANSACT_DELIVERY lines; on the AfroTransact row, the total earned (4250)
     */
    record Recipient(String recipient, String kind, long itemsMinor, long shippingMinor, long taxMinor,
                     long commissionMinor, long fbaFeesMinor, long deliveryFeesMinor, long stripeFeeMinor,
                     long netMinor) {
    }
}
