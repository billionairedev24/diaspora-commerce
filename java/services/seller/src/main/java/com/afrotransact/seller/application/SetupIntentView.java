package com.afrotransact.seller.application;

/**
 * A Stripe SetupIntent for adding a card or US bank account to the organisation's billing (S103): Seller Center
 * confirms it with Stripe.js (or the mobile PaymentSheet) and then registers the resulting {@code pm_…} with
 * {@code POST /v1/orgs/{orgId}/billing/payment-methods}.
 */
public record SetupIntentView(String customerId, String setupIntentId, String clientSecret) {
}
