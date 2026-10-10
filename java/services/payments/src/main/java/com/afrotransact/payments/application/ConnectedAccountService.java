package com.afrotransact.payments.application;

/** Stripe Connect account of each organisation (registered by seller-service after onboarding). */
public interface ConnectedAccountService {

    void register(String orgId, String stripeAccountId);

    String accountOf(String orgId);
}
