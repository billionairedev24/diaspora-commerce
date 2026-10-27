package com.afrotransact.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.domain.Attribution;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.CartClient;

/**
 * Input of the checkout saga: the cart and address snapshot taken with the customer's token when the request arrived
 * (the token itself never enters the workflow history).
 *
 * @param orderId               order id allocated for this Idempotency-Key
 * @param orderNumber           AT-2026-XXXXXX
 * @param customerId            identity subject
 * @param idempotencyKey        client's Idempotency-Key
 * @param workflowId            checkout-&lt;orderId&gt;
 * @param currency              cart currency
 * @param shipTo                delivery address snapshot
 * @param items                 selected cart lines
 * @param selections            delivery choice per package
 * @param paymentTimeoutSeconds how long to wait for the authorisation (reservation TTL)
 * @param requestedAt           request time
 * @param couponCode            coupon to validate and redeem (null: none)
 * @param attribution           storefront attribution (S272; null when none)
 * @param preorderFulfilment    door / pickup per pre-order window chosen in the cart (S272; window id → DOOR|PICKUP)
 * @param lateAdd               set for a customer-service late pre-order add (S272): the window may be CLOSED
 * @param savePaymentMethod     the PaymentIntent saves the shopper's new method (setup_future_usage=off_session, S282)
 * @param useCredit             pay with the shopper's AfroTransact credit first; the card pays only the rest (S320)
 */
public record CheckoutInput(String orderId, String orderNumber, String customerId, String idempotencyKey,
                            String workflowId, String currency, ShipTo shipTo, List<CartClient.Item> items,
                            List<Selection> selections, long paymentTimeoutSeconds, Instant requestedAt,
                            String couponCode, Attribution attribution, Map<String, String> preorderFulfilment,
                            LateAdd lateAdd, boolean savePaymentMethod, boolean useCredit) {

    public CheckoutInput {
        preorderFulfilment = preorderFulfilment == null ? Map.of() : Map.copyOf(preorderFulfilment);
    }

    /** An input that does not use credit (late adds, older workflow histories). */
    public CheckoutInput(String orderId, String orderNumber, String customerId, String idempotencyKey,
                         String workflowId, String currency, ShipTo shipTo, List<CartClient.Item> items,
                         List<Selection> selections, long paymentTimeoutSeconds, Instant requestedAt,
                         String couponCode, Attribution attribution, Map<String, String> preorderFulfilment,
                         LateAdd lateAdd, boolean savePaymentMethod) {
        this(orderId, orderNumber, customerId, idempotencyKey, workflowId, currency, shipTo, items, selections,
                paymentTimeoutSeconds, requestedAt, couponCode, attribution, preorderFulfilment, lateAdd,
                savePaymentMethod, false);
    }

    /** An input that does not save the payment method (late adds, older workflow histories). */
    public CheckoutInput(String orderId, String orderNumber, String customerId, String idempotencyKey,
                         String workflowId, String currency, ShipTo shipTo, List<CartClient.Item> items,
                         List<Selection> selections, long paymentTimeoutSeconds, Instant requestedAt,
                         String couponCode, Attribution attribution, Map<String, String> preorderFulfilment,
                         LateAdd lateAdd) {
        this(orderId, orderNumber, customerId, idempotencyKey, workflowId, currency, shipTo, items, selections,
                paymentTimeoutSeconds, requestedAt, couponCode, attribution, preorderFulfilment, lateAdd, false);
    }

    /** An input without pre-order details (tests, older workflow histories). */
    public CheckoutInput(String orderId, String orderNumber, String customerId, String idempotencyKey,
                         String workflowId, String currency, ShipTo shipTo, List<CartClient.Item> items,
                         List<Selection> selections, long paymentTimeoutSeconds, Instant requestedAt,
                         String couponCode) {
        this(orderId, orderNumber, customerId, idempotencyKey, workflowId, currency, shipTo, items, selections,
                paymentTimeoutSeconds, requestedAt, couponCode, null, Map.of(), null);
    }

    /**
     * A late pre-order add: every line must be a pre-order of {@code windowId}, which may be OPEN or CLOSED.
     *
     * @param windowId the pre-order window the staff member adds to
     */
    public record LateAdd(String windowId) {
    }

    /** True when the shopper entered a coupon. */
    public boolean hasCoupon() {
        return couponCode != null && !couponCode.isBlank();
    }

    /**
     * Delivery choice for one cart package ({@code <sellerOrgId>:<fulfilment>}).
     *
     * @param option carrier | express | afrotransact_delivery | pickup
     */
    public record Selection(String packageId, String option, String serviceLevel, String pickupLocationId,
                            DeliveryPreferences preferences) {
    }
}
