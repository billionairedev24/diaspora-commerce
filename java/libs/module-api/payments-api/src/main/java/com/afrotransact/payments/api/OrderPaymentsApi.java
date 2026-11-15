package com.afrotransact.payments.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the orders module asks payments in-process: the payment of an order, its captures and refunds, the delivery quote, the
 * settlement start and the order split. The calls behind {@code /v1/payments...}, {@code /v1/delivery-pricing/quote},
 * {@code /v1/settlements/...} and {@code /v1/orders/{id}/split} (scope {@code payments:service}). Every call runs under an
 * in-process caller principal; payments' own permission checks apply and a caller without the scope is refused with
 * {@code com.afrotransact.module.ModuleApiException} status 403. Other failures arrive with the status and code of the
 * endpoint ({@code payments.card-declined} 402, {@code payments.already-captured} 409, ...). The idempotency key of each call is
 * passed in, as the {@code Idempotency-Key} header is over HTTP.
 */
public interface OrderPaymentsApi {

    /** Creates (or replays) the order's PaymentIntent. */
    PaymentInfo create(NewPayment request, String idempotencyKey);

    /** @param refresh read the intent's status from Stripe first */
    PaymentInfo get(String paymentId, boolean refresh);

    /** @return the order's payment, empty when it has none (HTTP 404) */
    Optional<PaymentInfo> findByOrder(String orderId);

    /** Cancels an uncaptured payment (releases the authorisation). */
    void cancel(String paymentId, String reason, String idempotencyKey);

    CaptureInfo capture(String paymentId, CaptureRequest request, String idempotencyKey);

    /** @return the id of the refund */
    String refund(String paymentId, String idempotencyKey, List<RefundLine> lines, String reason);

    /** Order delivered: starts the settlement hold. */
    void delivered(String orderId, Instant deliveredAt);

    /** One shipment delivered: starts that shipment's settlement hold. */
    void shipmentDelivered(String orderId, String shipmentId, Instant deliveredAt);

    SplitInfo split(String orderId);

    /** Prices an AfroTransact Delivery trip from a seller's store under the fee schedule in force. */
    DeliveryQuoteInfo quoteDelivery(String sellerOrgId, double distanceMiles, int packages);

    /**
     * @param shopperId   the AfroTransact customer id; needed with the off-session, saved-method and credit options
     * @param metadata    stored with the payment ({@code capture_plan} decides how captures follow shipments)
     * @param waivers     the delivery fees the shopper's membership waived, recorded as benefits used
     */
    record NewPayment(String orderId, long amountMinor, String currency, Instant placedAt, Map<String, String> metadata,
                      List<Line> lines, String shopperId, List<String> paymentMethodTypes, boolean offSession,
                      boolean saveForOffSession, boolean useCredit, List<Waiver> waivers) {

        public NewPayment {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            lines = lines == null ? List.of() : List.copyOf(lines);
            paymentMethodTypes = paymentMethodTypes == null ? List.of() : List.copyOf(paymentMethodTypes);
            waivers = waivers == null ? List.of() : List.copyOf(waivers);
        }
    }

    /** @param fulfilment FBA, SELF, AFROTRANSACT_DELIVERY, ... as payments names it */
    record Line(String lineId, String sellerOrg, String category, String fulfilment, int quantity, long itemsMinor,
                long shippingMinor, long taxMinor, String taxJurisdiction, long shippingTaxMinor, long deliveryFeeMinor) {
    }

    /** @param benefit FREE_DELIVERY or FREE_SHIPPING */
    record Waiver(String ref, String benefit, long amountMinor, String tierCode) {
    }

    /** @param clientSecret for Stripe.js confirmation; only the calling service gets it */
    record PaymentInfo(String paymentId, String status, String clientSecret, long amountMinor, String failureMessage,
                   long creditMinor) {
    }

    record CaptureRequest(String shipmentId, List<String> lineIds, long amountMinor, boolean finalCapture) {
    }

    record CaptureInfo(String captureId, long amountMinor, List<String> lineIds) {

        public CaptureInfo {
            lineIds = lineIds == null ? List.of() : List.copyOf(lineIds);
        }
    }

    record RefundLine(String lineId, long itemsMinor, long shippingMinor, long taxMinor) {
    }

    record DeliveryQuoteInfo(boolean available, String unavailableReason, long customerChargeMinor, long sellerFeeMinor,
                         int scheduleVersion) {
    }

    record SplitInfo(String orderId, long chargeMinor, long stripeFeeMinor, long taxCollectedMinor, String currency,
                 List<Recipient> recipients) {

        public SplitInfo {
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }

    record Recipient(String recipient, String kind, long itemsMinor, long shippingMinor, long taxMinor,
                     long commissionMinor, long fbaFeesMinor, long deliveryFeesMinor, long stripeFeeMinor, long netMinor) {
    }
}
