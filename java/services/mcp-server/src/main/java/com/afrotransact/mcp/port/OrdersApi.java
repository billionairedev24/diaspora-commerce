package com.afrotransact.mcp.port;

import java.util.Map;

/** orders-service orders and shipments. */
public interface OrdersApi {
    /** {@code GET /v1/orders/{number or id}}: the caller sees what their token may see (a seller only its own shipments). */
    Downstream order(String orderId, String bearerToken);

    /** {@code GET /v1/seller/shipments}: the shipments of the token's organisation, newest first, paged by cursor. */
    Downstream sellerShipments(String status, String cursor, int limit, String bearerToken);

    /** {@code POST /v1/shipments/{id}/transitions}: one step; {@code body} has {@code to} and, for a pickup handover, {@code handoverCode}. */
    Downstream transitionShipment(String shipmentId, Map<String, Object> body, String bearerToken);

    /** {@code POST /v1/shipments/{id}/tracking}: the carrier label of a seller-fulfilled carrier shipment. */
    Downstream attachTracking(String shipmentId, String carrier, String trackingNumber, String bearerToken);
}
