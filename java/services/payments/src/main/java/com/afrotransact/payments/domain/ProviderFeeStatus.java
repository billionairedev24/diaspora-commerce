package com.afrotransact.payments.domain;

/** Collection state of a provider fee. */
public enum ProviderFeeStatus {
    /** Accrued in the ledger; the account debit has not succeeded yet (retried, reserved out of payouts). */
    OUTSTANDING,
    /** Debited from the provider's connected account. */
    COLLECTED
}
