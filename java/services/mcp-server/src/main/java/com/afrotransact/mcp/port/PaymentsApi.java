package com.afrotransact.mcp.port;

/** payments-service: the payout balance and payout requests of an organisation. */
public interface PaymentsApi {
    /** {@code GET /v1/payouts/balance?orgId=}: available, on hold per order (with release dates), in transit, paid, pauses. */
    Downstream payoutBalance(String orgId, CallerCredentials credentials);

    /** {@code GET /v1/payouts/requests?orgId=}: the organisation's payout requests, newest first. */
    Downstream payoutRequests(String orgId, int limit, CallerCredentials credentials);
}
