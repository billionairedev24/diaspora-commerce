package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.ProviderFee;

/**
 * Fees providers owe AfroTransact (S220: the late provider-cancellation fee). A fee is accrued in the ledger when
 * recorded and collected from the provider's Stripe connected account with an account debit; while it cannot be
 * collected (no account, insufficient balance) it stays OUTSTANDING, is retried, and is reserved out of the
 * organisation's payout balance.
 */
public interface ProviderFeeService {

    /** Records (once per event and per booking) and tries to collect the fee. Returns false for a duplicate. */
    boolean lateCancellation(ProviderFeeNotice notice);

    /** Retries due OUTSTANDING fees (job). Returns how many were collected. */
    int collectOutstanding(int maxFees);

    /** The organisation's fees, newest first (its members, or staff). */
    List<ProviderFee> ofOrg(String orgId);
}
