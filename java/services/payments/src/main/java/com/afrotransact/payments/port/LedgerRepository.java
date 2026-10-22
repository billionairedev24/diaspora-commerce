package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.payments.domain.AccountBalance;
import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.JournalType;
import com.afrotransact.payments.domain.StatementLine;

/** Append-only journal store (UPDATE / DELETE are rejected by database triggers). */
public interface LedgerRepository {

    boolean exists(String journalKey);

    void insert(Journal journal, String postedBy, Instant postedAt);

    Optional<Journal> findByKey(String journalKey);

    List<Journal> journalsOfOrder(String orderId);

    /** Journals booked against one Stripe object (charge, refund, transfer, payout …). */
    List<Journal> journalsByStripeRef(String stripeRef);

    /** Balances of every (sub-)account, or of the lines attributed to {@code orgId} when present. */
    List<AccountBalance> balances(Optional<String> orgId);

    /** Seller payable (2000:org) balance per order, non-zero only. */
    Map<String, Long> payableByOrder(String orgId, String currency);

    /** Seller payable balance per organisation for one order, non-zero only. */
    Map<String, Long> payableByOrg(String orderId, String currency);

    /**
     * Balance (credit positive) of the organisation's payable lines attributed to no order: label charges and credits
     * not yet settled against a transfer. Negative while the seller owes AfroTransact for labels.
     */
    long unattributedPayable(String orgId, String currency);

    /**
     * AfroTransact Delivery pickup fees the organisation paid (S259): credits to 4250 attributed to it at capture minus
     * the fees given back by customer-fault refunds.
     */
    long deliveryFeesPaid(String orgId, String currency);

    /**
     * The seller statement: movements of 2000:org in [from, to) by posting time, newest first, with the label, shipment
     * and tracking number of label lines.
     */
    List<StatementLine> statement(String orgId, String currency, Instant from, Instant to, int limit,
                                  Set<JournalType> kinds);

    /** Net movement (debit positive) on 1000 Stripe clearing per Stripe reference for journals posted in [from, to). */
    Map<String, Long> clearingByRef(Instant from, Instant to, String currency);
}
