package com.afrotransact.seller.port;

/**
 * Tells payments-service which Stripe Connect account id belongs to an organisation so settlement transfers can run.
 */
public interface ConnectedAccountRegistry {

    void register(String orgId, String stripeAccountId);
}
