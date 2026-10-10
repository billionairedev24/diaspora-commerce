package com.afrotransact.orders.application;

import com.afrotransact.orders.domain.MissedDeliveryChoice;

/** Missed pre-order deliveries (S272): flag them, and let the customer choose a refund or a new date. */
public interface PreorderDeliveryService {

    /**
     * Flags placed PREORDER shipments still undelivered after their delivery date and publishes
     * {@code orders.preorder.delivery_missed} once per shipment. Returns how many were flagged.
     */
    int flagMissedDeliveries();

    /**
     * The customer's answer to a missed delivery: REFUND cancels the shipment (full refund, delivery fee included,
     * through the usual cancellation follow-up) while it has not left for delivery; WAIT keeps it for a new date.
     * Publishes {@code orders.preorder.missed_delivery_answered}.
     *
     * @param order order number or id of one of the caller's orders
     */
    OrderView answerMissedDelivery(String order, String shipmentId, MissedDeliveryChoice choice);
}
