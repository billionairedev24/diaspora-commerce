package com.afrotransact.mcp.port;

/** payments-service: staff payment split of an order and the payout balance of an organisation. */
public interface PaymentsApi {
    Downstream orderSplit(String orderId, String bearerToken);

    /** {@code GET /v1/payouts/balance?orgId=}: available, on hold per order (with release dates), in transit, paid, pauses. */
    Downstream payoutBalance(String orgId, String bearerToken);

    /** {@code GET /v1/payouts/requests?orgId=}: the organisation's payout requests, newest first. */
    Downstream payoutRequests(String orgId, int limit, String bearerToken);
}
