package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.payments.domain.CreditEntry;

/**
 * The append-only credit ledger of every customer. There is no update and no delete: an entry is added, and the
 * customer's balance row moves with it in the same transaction.
 */
public interface CreditLedgerRepository {

    /**
     * Locks the customer's account row (creating it with a zero balance) until the transaction ends and returns the
     * balance, so concurrent spends of one customer queue up and never overspend.
     */
    long lockBalance(String customerId, String currency, Instant now);

    /** The balance now (0 for a customer who never had credit). */
    long balance(String customerId);

    Optional<CreditEntry> findByKey(String idempotencyKey);

    /** Appends the entry and moves the account balance to {@code entry.balanceAfterMinor()}; call with the row locked. */
    void append(CreditEntry entry);

    /** Newest first; {@code before} (exclusive) pages backwards, null for the newest. */
    List<CreditEntry> entries(String customerId, Instant before, int limit);

    List<CreditEntry> entriesOfOrder(String orderId);

    /** Sum of the entries of the customer (control figure: equals {@link #balance}). */
    long sumOfEntries(String customerId);

    /** Totals for finance: credit outstanding, accounts holding it, and Σ amounts by "KIND/SOURCE". */
    Totals totals();

    /** Σ of all balances, accounts with a balance and Σ of entry amounts by {@code KIND/SOURCE}. */
    record Totals(long outstandingMinor, long accountsWithBalance, Map<String, Long> flowsMinor) {
        public Totals {
            flowsMinor = Map.copyOf(flowsMinor);
        }
    }
}
