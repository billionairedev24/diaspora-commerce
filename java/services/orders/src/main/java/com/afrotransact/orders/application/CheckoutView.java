package com.afrotransact.orders.application;

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
                           String paymentClientSecret, Long totalMinor, String currency, List<Shipment> shipments) {

    /** One shipment of the new order, with the customer's handover code when one is required. */
    public record Shipment(String shipmentId, String label, String eta, LocalDate promisedBy, String deliveryMethod,
                           String deliveryCode) {
    }
}
