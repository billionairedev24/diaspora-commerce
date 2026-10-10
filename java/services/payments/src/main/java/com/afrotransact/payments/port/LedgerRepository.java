package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.payments.domain.AccountBalance;
import com.afrotransact.payments.domain.Journal;

/** Append-only journal store (UPDATE / DELETE are rejected by database triggers). */
public interface LedgerRepository {

    boolean exists(String journalKey);

    void insert(Journal journal, String postedBy, Instant postedAt);

    Optional<Journal> findByKey(String journalKey);

    List<Journal> journalsOfOrder(String orderId);

    /** Balances of every (sub-)account, or of the lines attributed to {@code orgId} when present. */
    List<AccountBalance> balances(Optional<String> orgId);

    /** Seller payable (2000:org) balance per order, non-zero only. */
    Map<String, Long> payableByOrder(String orgId, String currency);

    /** Seller payable balance per organisation for one order, non-zero only. */
    Map<String, Long> payableByOrg(String orderId, String currency);

    /** Net movement (debit positive) on 1000 Stripe clearing per Stripe reference for journals posted in [from, to). */
    Map<String, Long> clearingByRef(Instant from, Instant to, String currency);
}
