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

    private OrdersEvents() {
    }
}
