package com.afrotransact.payments.domain;

/**
 * One Stripe object whose movement on 1000 Stripe clearing differs from Stripe's balance transactions.
 *
 * @param ledgerMinor net movement on 1000 Stripe clearing in the ledger
 * @param stripeMinor net of Stripe's balance transactions for the same source
 */
public record ReconciliationDrift(String stripeRef, long ledgerMinor, long stripeMinor) {

    public long difference() {
        return ledgerMinor - stripeMinor;
    }
}
