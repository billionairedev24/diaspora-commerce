package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.port.StripeGateway.ConnectedBalanceSnapshot;
import com.afrotransact.payments.port.StripeGateway.ObjectSnapshot;

/**
 * Finance's lookup of one Stripe object next to what the ledger booked against it, so a payout, transfer, charge or
 * refund can be checked against Stripe without leaving the console.
 */
public interface StripeLookupService {

    /**
     * @param ref   Stripe id (po_, tr_, pi_, ch_, re_, txn_, dp_, acct_)
     * @param orgId the seller whose connected account holds a payout (or whose balance to read); may be null
     */
    StripeLookup lookup(String ref, String orgId);

    /**
     * @param object   what Stripe returned; null when it has no such object or could not be asked
     * @param problem  why {@code object} is missing ("Stripe has no object with that id", or Stripe's error)
     * @param journals what the ledger booked against this id
     * @param balance  the connected account's Stripe balance, when one was asked for and read
     */
    record StripeLookup(String ref, ObjectSnapshot object, String problem, List<Journal> journals,
                        String connectedAccount, ConnectedBalanceSnapshot balance) {
    }
}
