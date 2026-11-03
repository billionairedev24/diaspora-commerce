package com.afrotransact.seller.port;

import java.util.Set;

import com.afrotransact.seller.domain.RequirementsSnapshot;

/**
 * Stripe Connect: connected accounts, embedded-component sessions and verification results. Seller accounts are
 * Stripe-collected (Stripe keeps the liability and KYC), so once onboarding started the platform can't change identity,
 * tax or bank details through the API: Stripe's embedded components collect and change them, and this port only
 * reads what Stripe holds.
 */
public interface StripeConnectGateway {

    /** Creates the connected account (idempotent per organisation) and returns its id ({@code acct_…}). */
    String createAccount(CreateConnectedAccount request);

    /**
     * Account Session for the embedded components, e.g. account_onboarding, account_management and
     * notification_banner. Every component keeps Stripe's defaults (Stripe collects the payout bank account), so
     * their features agree, as Stripe requires.
     */
    AccountSession createAccountSession(String accountId, Set<String> components);

    /** Verification results (requirements, flags, capability statuses) and the payout bank account Stripe holds. */
    RequirementsSnapshot retrieveAccount(String accountId);
}
