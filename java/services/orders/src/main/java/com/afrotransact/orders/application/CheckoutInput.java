package com.afrotransact.orders.application;

import java.time.Instant;
import java.util.List;

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
 */
public record CheckoutInput(String orderId, String orderNumber, String customerId, String idempotencyKey,
                            String workflowId, String currency, ShipTo shipTo, List<CartClient.Item> items,
                            List<Selection> selections, long paymentTimeoutSeconds, Instant requestedAt,
                            String couponCode) {

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
