package com.afrotransact.orders.api;

import java.util.Map;

import com.afrotransact.module.EndUserActor;

/**
 * Orders and shipments for the signed-in person (MCP tools): the in-process equivalent of relaying their bearer token to
 * orders-service. Orders' own use cases decide as for the HTTP request: a customer sees their own orders, a seller organisation only its
 * own shipments, staff with {@code orders:read} everything without the handover codes, and a shipment step is allowed only to the actor
 * the state machine names for it (403 {@code orders.transition-forbidden}). The calling module needs {@value #READ_SCOPE} for the
 * reads and {@value #WRITE_SCOPE} for the two shipment writes. Results are the JSON tree of the HTTP response; failures are
 * {@code ModuleApiException} with the HTTP problem's status and code.
 */
public interface OrdersUserApi {

    /** Delegation scope for the read methods. */
    String READ_SCOPE = "orders:user.read";

    /** Delegation scope for the shipment writes. */
    String WRITE_SCOPE = "orders:user.write";

    /** {@code GET /v1/orders/{number or id}}. */
    Object order(EndUserActor user, String orderId);

    /** {@code GET /v1/orders?customer=me}: the person's own orders, newest first, paged by cursor. */
    Object myOrders(EndUserActor user, String cursor, int limit);

    /** {@code GET /v1/seller/shipments}: the shipments of the person's organisation, newest first, paged by cursor. */
    Object sellerShipments(EndUserActor user, String status, String cursor, int limit);

    /** {@code GET /v1/orders/{number}/payment-split}: staff see every recipient, a seller its own row. */
    Object paymentSplit(EndUserActor user, String orderNumber);

    /** {@code POST /v1/shipments/{id}/transitions}: {@code body} has {@code to} and optionally {@code reason} and {@code handoverCode}. */
    Object transitionShipment(EndUserActor user, String shipmentId, Map<String, Object> body);

    /** {@code POST /v1/shipments/{id}/tracking}: the carrier label of a seller-fulfilled carrier shipment. */
    Object attachTracking(EndUserActor user, String shipmentId, String carrier, String trackingNumber);
}
