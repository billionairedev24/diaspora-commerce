package com.afrotransact.mcp.port;

/** seller-service: an organisation's Stripe/account health (what is still needed, whether payouts are paused). */
public interface SellerApi {
    /** {@code GET /v1/orgs/{orgId}/account-health}. */
    Downstream accountHealth(String orgId, String bearerToken);
}
