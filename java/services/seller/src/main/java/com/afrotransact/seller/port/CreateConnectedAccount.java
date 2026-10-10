package com.afrotransact.seller.port;

/**
 * A new Stripe connected account: platform-controlled (Stripe collects requirements in embedded components, the
 * platform pays fees and owns losses, no Stripe dashboard) with manual payouts.
 *
 * @param businessType Stripe business_type: individual, company or non_profit
 */
public record CreateConnectedAccount(String orgId, String applicationId, String country, String email,
                                     String businessType, String idempotencyKey) {
}
