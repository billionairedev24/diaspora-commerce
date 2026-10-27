package com.afrotransact.payments.application;

import com.afrotransact.payments.domain.CreditEntry;
import com.afrotransact.payments.domain.CreditKind;
import com.afrotransact.payments.domain.CreditSource;
import com.afrotransact.payments.domain.Money;

/**
 * The single base of AfroTransact credit (ADR 0006): every grant, spend, refund and adjustment of a customer's credit
 * is an entry in an append-only ledger. The balance is the sum of the entries. Nothing here pays credit out in cash:
 * there is no operation that turns a balance into money, a card refund or a transfer to someone else.
 *
 * <p>Every operation is atomic, idempotent per key and safe under concurrency: the customer's account row is locked
 * in a SERIALIZABLE transaction, so two orders spending the same credit queue up and the second sees what is left.
 * The caller's transaction is joined when there is one (checkout spends credit in the transaction that creates the
 * payment).
 */
interface CreditLedger {

    /**
     * Adds credit (grant, refund or positive adjustment). A replay of the same key returns the first entry and adds
     * nothing; the same key for another customer or amount is a conflict.
     */
    CreditEntry add(Add command);

    /**
     * Spends up to {@code command.maxMinor()} of the customer's credit on an order: the smaller of the balance and the
     * maximum, less what keeps the card remainder at or above {@code command.minRemainderMinor()} (Stripe cannot
     * charge less than 0.50). Never more than the balance. A replay returns the first outcome.
     */
    Spend spend(SpendCommand command);

    /** Takes credit back (staff correction); fails when the customer no longer has that much. */
    CreditEntry take(Take command);

    /** The balance now. */
    long balance(String customerId);

    /**
     * @param postExpense book Dr 5200 program cost / Cr 2600 for this grant; false when the caller books it (gift card
     *                    redeemed, a refund whose journal already credited 2600, a cancelled order)
     */
    record Add(String customerId, CreditKind kind, Money amount, CreditSource source, String reason, String actor,
               String orderId, String reference, String idempotencyKey, boolean postExpense) {
    }

    record SpendCommand(String customerId, Money maxAmount, long minRemainderMinor, String orderId, String actor,
                        String idempotencyKey) {
    }

    record Take(String customerId, Money amount, String reason, String actor, String reference,
                String idempotencyKey) {
    }

    /** What was spent (zero when the customer had no credit) and the entry that recorded it. */
    record Spend(Money spent, CreditEntry entry, long balanceAfterMinor) {
    }
}
