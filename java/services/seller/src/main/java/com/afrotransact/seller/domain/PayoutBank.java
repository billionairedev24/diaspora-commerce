package com.afrotransact.seller.domain;

import java.util.Set;

/**
 * The payout (external) bank account Stripe holds for a connected account, as Stripe reports it: never the full
 * numbers. Stripe collects and changes it in its embedded components (account onboarding and account management);
 * AfroTransact only mirrors it from the account and {@code account.external_account.*} webhooks.
 *
 * @param status Stripe's bank account status: {@code new | validated | verified | verification_failed | errored}
 */
public record PayoutBank(String id, String bankName, String last4, String status) {

    private static final Set<String> FAILED = Set.of("verification_failed", "errored");

    /** Stripe rejected the account (a payout bounced or the micro-deposits failed): the seller must change it. */
    public boolean failed() {
        return status != null && FAILED.contains(status);
    }

    /** Stripe accepted the account: {@code new} (not yet paid out to), {@code validated} or {@code verified}. */
    public boolean usable() {
        return last4 != null && !failed();
    }
}
