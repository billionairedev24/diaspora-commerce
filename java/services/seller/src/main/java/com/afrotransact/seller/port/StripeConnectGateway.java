package com.afrotransact.seller.port;

import java.util.Set;

import com.afrotransact.seller.domain.RequirementsSnapshot;

/** Stripe Connect: connected accounts, embedded-component sessions, verification results and requirement answers. */
public interface StripeConnectGateway {

    /** Creates the connected account (idempotent per organisation) and returns its id ({@code acct_…}). */
    String createAccount(CreateConnectedAccount request);

    /** Account Session for the embedded components, e.g. account_onboarding and account_management. */
    AccountSession createAccountSession(String accountId, Set<String> components);

    /** Verification results only: requirements, flags and capability statuses. */
    RequirementsSnapshot retrieveAccount(String accountId);

    /** Attaches a bank account token ({@code btok_…}) created client-side as the payout external account. */
    void attachExternalAccount(String accountId, String bankAccountToken);

    /**
     * Sends the bank account the applicant typed on "Payouts" to Stripe as the default payout (external) account.
     * The numbers go to Stripe only; the caller keeps the last 4 digits.
     */
    LinkedBankAccount attachBankAccount(String accountId, BankAccountDetails bank);

    /** Uploads a document to Stripe Files with purpose {@code account_requirement}; returns {@code file_…}. */
    String uploadRequirementFile(String accountId, MediaAsset asset);

    /** Forwards an Account health answer to Accounts / Persons update. */
    void submitRequirement(String accountId, RequirementSubmission submission);
}
