package com.afrotransact.orders.application;

/**
 * Event types published through the outbox on {@code at.order.events} (aggregate "order", key = order id), see
 * contracts/asyncapi/fragments/orders.yaml.
 */
public final class OrdersEvents {

    public static final String AGGREGATE = "order";
    public static final String ORDER_PLACED = "orders.order.placed";
    public static final String ORDER_CANCELLED = "orders.order.cancelled";
    public static final String SHIPMENT_STATUS_CHANGED = "orders.shipment.status_changed";
    public static final String SHIPMENT_DELIVERED = "orders.shipment.delivered";
    public static final String SHIPMENT_CANCELLED = "orders.shipment.cancelled";
    /** S272: a PREORDER shipment passed its delivery date undelivered; the customer chooses refund or wait. */
    public static final String PREORDER_DELIVERY_MISSED = "orders.preorder.delivery_missed";
    /** S272: the customer answered a missed pre-order delivery (REFUND or WAIT). */
    public static final String PREORDER_MISSED_DELIVERY_ANSWERED = "orders.preorder.missed_delivery_answered";
    /** S272: a late pre-order add waits for the customer to pay its payment link (sent by SMS). */
    public static final String PREORDER_PAYMENT_LINK_CREATED = "orders.preorder.payment_link_created";

    private OrdersEvents() {
    }
}
