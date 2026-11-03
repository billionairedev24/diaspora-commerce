package com.afrotransact.seller.port;

/**
 * A new Stripe connected account: platform-controlled (Stripe collects requirements in embedded components, the
 * platform pays fees and owns losses, no Stripe dashboard) with manual payouts.
 *
 * @param businessType       Stripe business_type: individual, company or non_profit
 * @param website            the business website Stripe is given: the business's own, else its AfroTransact page
 *                           (null leaves it to the embedded onboarding)
 * @param productDescription what the business sells or does (null leaves it to the embedded onboarding)
 */
public record CreateConnectedAccount(String orgId, String applicationId, String country, String email,
                                     String businessType, String idempotencyKey, String website,
                                     String productDescription) {

    /** An account without a pre-filled business profile. */
    public CreateConnectedAccount(String orgId, String applicationId, String country, String email,
                                  String businessType, String idempotencyKey) {
        this(orgId, applicationId, country, email, businessType, idempotencyKey, null, null);
    }
}
