package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Answer of {@code POST /v1/checkout}: confirm the payment with {@code clientSecret} (Stripe.js / PaymentSheet); the
 * order is placed once payments reports the authorisation.
 *
 * @param status             awaiting_payment | placed | processing
 * @param paymentClientSecret same as clientSecret (gateway-bff field name)
 */
public record CheckoutView(String orderId, String orderNumber, String status, String clientSecret,
                           String paymentClientSecret, Long totalMinor, String currency,
                           Long itemsMinor, Long shippingMinor, Long taxMinor, Long discountMinor,
                           List<Shipment> shipments, Preorder preorder) {

    public CheckoutView(String orderId, String orderNumber, String status, String clientSecret,
                        String paymentClientSecret, Long totalMinor, String currency, List<Shipment> shipments) {
        this(orderId, orderNumber, status, clientSecret, paymentClientSecret, totalMinor, currency,
                null, null, null, null, shipments, null);
    }

    /**
     * S272 "You're in" facts of an order with a pre-order package (null otherwise).
     *
     * @param number         the order number (the pre-order's reference for support)
     * @param windowName     e.g. Lubbock Pre-order #7
     * @param closesAt       cancel for a full refund until then
     * @param timeZone       business time zone the dates are shown in
     * @param otherItemCount units of the same order that ship now
     */
    public record Preorder(String number, String windowName, String windowId, String windowCode,
                           LocalDate deliversOn, Instant closesAt, String timeZone, String fulfilment,
                           int otherItemCount) {
    }

    /**
     * One shipment of the new order, with the customer's handover code when one is required.
     *
     * @param fulfilmentType      PREORDER for a community pre-order shipment (S272)
     * @param preorderWindowId    the pre-order window (PREORDER only)
     * @param preorderFulfilment  DOOR | PICKUP (PREORDER only)
     * @param cancellableUntil    the window's close: cancel for a full refund until then (PREORDER only)
     */
    public record Shipment(String shipmentId, String label, String eta, LocalDate promisedBy, String deliveryMethod,
                           String deliveryCode, String fulfilmentType, String preorderWindowId,
                           String preorderFulfilment, Instant cancellableUntil) {

        public Shipment(String shipmentId, String label, String eta, LocalDate promisedBy, String deliveryMethod,
                        String deliveryCode) {
            this(shipmentId, label, eta, promisedBy, deliveryMethod, deliveryCode, null, null, null, null);
        }
    }
}
