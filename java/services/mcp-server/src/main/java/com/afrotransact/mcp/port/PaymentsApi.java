package com.afrotransact.mcp.port;

/** payments-service staff view of an order's payment split. */
public interface PaymentsApi {
    Downstream orderSplit(String orderId, String bearerToken);
}
