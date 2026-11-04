package com.afrotransact.seller.domain;

/**
 * What Account health shows for one Stripe requirement, and where it is resolved. Seller accounts are Stripe-collected
 * (Stripe keeps the liability and KYC), so AfroTransact can't update identity, tax or bank details through the API
 * once onboarding started: every requirement is answered in Stripe's embedded components.
 *
 * @param subject   {@code company}, {@code individual}, {@code representative}, a person id, or {@code account}
 * @param component the embedded component that resolves it: {@code account_management} for the payout bank account,
 *                  {@code account_onboarding} for everything else (it collects whatever Stripe currently needs)
 */
public record RequirementForm(String requirement, RequirementKind kind, String label, String subject, String why,
                              String component) {

    public static final String ACCOUNT_ONBOARDING = "account_onboarding";
    public static final String ACCOUNT_MANAGEMENT = "account_management";
}
