package com.afrotransact.orders.application;

import java.time.Instant;

/**
 * Whether a customer received a product (S324 verified-purchase reviews).
 *
 * @param delivered   true when a shipment with a line for the product ATIN was delivered to the customer
 * @param orderId     the delivering order (null when not delivered)
 * @param orderNumber the order number (null when not delivered)
 * @param shipmentId  the delivered shipment (null when not delivered)
 * @param deliveredAt when it was delivered (null when not delivered or the time is unknown)
 */
public record PurchaseCheck(boolean delivered, String orderId, String orderNumber, String shipmentId,
                            Instant deliveredAt) {

    /** The customer has no delivered line for the product. */
    public static PurchaseCheck none() {
        return new PurchaseCheck(false, null, null, null, null);
    }
}
