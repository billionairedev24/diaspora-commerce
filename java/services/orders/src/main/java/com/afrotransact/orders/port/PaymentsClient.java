package com.afrotransact.orders.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** payments-service (SERVICE principal with payments:service). */
public interface PaymentsClient {

    /**
     * {@code POST /v1/payments}: PaymentIntent with manual capture; Idempotency-Key = order id (payments keys the Stripe
     * call {@code <orderId>:create-intent}). S272 late adds: {@code offSession} confirms it now on the customer's
     * default saved card; a pay-now late add is an ordinary intent the customer confirms on the storefront pay page.
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
     * @param useCredit S320: pay with the shopper's AfroTransact credit first; the PaymentIntent is for the rest and
     *                  there is none when credit pays everything
     * @param membershipWaivers S323: the shipping fees the shopper's membership waived, recorded by payments as the
     *                  benefits used
     */
    record NewIntent(String orderId, long amountMinor, String currency, Instant placedAt,
                     Map<String, String> metadata, List<Line> lines, String shopperId,
                     List<String> paymentMethodTypes, boolean offSession, boolean saveForOffSession,
                     boolean useCredit, List<WaivedFee> membershipWaivers) {

        public NewIntent {
            paymentMethodTypes = paymentMethodTypes == null ? List.of() : List.copyOf(paymentMethodTypes);
            membershipWaivers = membershipWaivers == null ? List.of() : List.copyOf(membershipWaivers);
        }

        /** An intent with no membership waivers. */
        public NewIntent(String orderId, long amountMinor, String currency, Instant placedAt,
                         Map<String, String> metadata, List<Line> lines, String shopperId,
                         List<String> paymentMethodTypes, boolean offSession, boolean saveForOffSession,
                         boolean useCredit) {
            this(orderId, amountMinor, currency, placedAt, metadata, lines, shopperId, paymentMethodTypes, offSession,
                    saveForOffSession, useCredit, List.of());
        }

        /** An intent that does not use credit. */
        public NewIntent(String orderId, long amountMinor, String currency, Instant placedAt,
                         Map<String, String> metadata, List<Line> lines, String shopperId,
                         List<String> paymentMethodTypes, boolean offSession, boolean saveForOffSession) {
            this(orderId, amountMinor, currency, placedAt, metadata, lines, shopperId, paymentMethodTypes, offSession,
                    saveForOffSession, false);
        }

        /** An intent that does not save the payment method. */
        public NewIntent(String orderId, long amountMinor, String currency, Instant placedAt,
                         Map<String, String> metadata, List<Line> lines, String shopperId,
                         List<String> paymentMethodTypes, boolean offSession) {
            this(orderId, amountMinor, currency, placedAt, metadata, lines, shopperId, paymentMethodTypes, offSession,
                    false);
        }

        public NewIntent(String orderId, long amountMinor, String currency, Instant placedAt,
                         Map<String, String> metadata, List<Line> lines, String shopperId) {
            this(orderId, amountMinor, currency, placedAt, metadata, lines, shopperId, List.of(), false);
        }

        public NewIntent(String orderId, long amountMinor, String currency, Instant placedAt,
                         Map<String, String> metadata, List<Line> lines) {
            this(orderId, amountMinor, currency, placedAt, metadata, lines, null);
        }
    }

    /** A shipping fee waived by the shopper's membership: the shipment, the benefit and the amount. */
    record WaivedFee(String ref, String benefit, long amountMinor, String tierCode) {
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
     * @param creditMinor the part of the order paid with AfroTransact credit (S320); the card pays the rest, and a
     *                    payment that credit pays in full is AUTHORIZED at once with no client secret
     */
    record Intent(String paymentId, String status, String clientSecret, long amountMinor, String failureMessage,
                  long creditMinor) {

        /** An intent paid without credit. */
        public Intent(String paymentId, String status, String clientSecret, long amountMinor, String failureMessage) {
            this(paymentId, status, clientSecret, amountMinor, failureMessage, 0);
        }
    }

    /** Capture of one shipment. */
    record CaptureRequest(String shipmentId, List<String> lineIds, long amountMinor, boolean finalCapture) {
    }

    /**
     * Capture result.
     *
     * @param lineIds the lines the capture took: more than the shipment's when the payment has no Stripe multicapture
     *                (a single-capture payment takes every line not captured yet with its first shipment)
     */
    record Capture(String captureId, long amountMinor, List<String> lineIds) {

        public Capture {
            lineIds = lineIds == null ? List.of() : List.copyOf(lineIds);
        }

        public Capture(String captureId, long amountMinor) {
            this(captureId, amountMinor, List.of());
        }
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
