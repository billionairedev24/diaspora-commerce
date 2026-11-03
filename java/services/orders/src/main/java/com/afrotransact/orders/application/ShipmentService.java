package com.afrotransact.orders.application;

/** Shipment status changes by sellers, staff, fulfilment, couriers and customers, through the state machine. */
public interface ShipmentService {

    /**
     * {@code POST /v1/shipments/{id}/transitions} by a seller, staff member or the fulfilment service. Handing a pickup
     * order over ({@code delivered}) needs the customer's 4-digit {@code handoverCode}; staff may confirm without it by
     * giving a reason.
     */
    ShipmentActionResult transition(String shipmentId, String to, String reason, String handoverCode,
                                    String idempotencyKey);

    /** The customer cancels one shipment of their order (until it is packed). */
    OrderView cancelByCustomer(String orderNumber, String shipmentId, String reason, String idempotencyKey);

    /** Driver-app event with proof (DRIVER principal with delivery:operate). */
    ShipmentActionResult courierEvent(String shipmentId, CourierEventCommand command);

    /** The seller (or staff) records the carrier label so tracking webhooks match the shipment. */
    OrderView attachTracking(String shipmentId, String carrier, String trackingNumber);

    /** Stop details for the driver app. */
    DeliveryBrief deliveryBrief(String shipmentId);
}
