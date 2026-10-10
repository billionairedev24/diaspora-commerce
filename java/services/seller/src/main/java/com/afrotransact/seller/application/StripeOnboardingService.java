package com.afrotransact.seller.application;

import com.afrotransact.seller.port.AccountSession;

/** Stripe Connect embedded onboarding for the applicant (and account management after approval). */
public interface StripeOnboardingService {

    /** Creates the connected account on first use, then an Account Session for the embedded components. */
    AccountSession accountSession();

    /** Re-reads verification results from Stripe (identity, tax ID match, requirements). */
    ApplicationView refreshVerification();
}
