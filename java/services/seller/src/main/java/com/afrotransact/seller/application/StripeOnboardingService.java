package com.afrotransact.seller.application;

import com.afrotransact.seller.port.AccountSession;

/**
 * Stripe Connect embedded components. Seller accounts are Stripe-collected: identity, tax details and the payout bank
 * account are entered in Stripe's components (account onboarding, account management, notification banner) and
 * AfroTransact only reads the results.
 */
public interface StripeOnboardingService {

    /** The applicant: creates the connected account on first use, then an Account Session for the components. */
    AccountSession accountSession();

    /**
     * An organisation's owner or finance member (Account health, after approval): an Account Session for the
     * components, to answer what Stripe needs or change the payout bank account. Staff can't open one (it edits the
     * seller's own account).
     */
    AccountSession accountSession(String orgId);

    /** Re-reads verification results from Stripe (identity, tax ID match, requirements). */
    ApplicationView refreshVerification();
}
