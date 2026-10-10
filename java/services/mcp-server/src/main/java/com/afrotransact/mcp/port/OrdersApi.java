package com.afrotransact.mcp.port;

import java.util.Map;

/** orders-service orders and shipments. */
public interface OrdersApi {
    Downstream order(String orderId, String bearerToken);

    Downstream sellerOrders(String orgId, String status, int page, int size, String bearerToken);

    Downstream updateShipmentStatus(String orderId, String shipmentId, Map<String, Object> change,
                                    String bearerToken);
}
