package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.domain.AdjustmentStatus;
import com.afrotransact.payments.domain.CreditAdjustment;
import com.afrotransact.payments.domain.CreditEntry;
import com.afrotransact.payments.domain.CreditSource;
import com.afrotransact.payments.domain.Money;

/**
 * AfroTransact credit as shoppers, staff and the other programs (referral, membership) see it. Credit only buys
 * products and services: no operation here converts it to cash, pays it out, refunds it to a card or moves it to
 * another customer.
 */
public interface CreditService {

    /** The signed-in shopper's balance and the terms of use (customer). */
    Balance myBalance();

    /** The signed-in shopper's history, newest first; {@code before} pages backwards (customer). */
    List<CreditEntry> myEntries(Instant before, int limit);

    /** One customer's balance (calling services: checkout shows what can be applied; staff with payments:read). */
    Balance balanceOf(String customerId);

    /** A customer's balance, history and adjustments (staff with payments:read). */
    CustomerCredit customer(String customerId, int limit);

    /**
     * Grants credit on behalf of a program (referral, membership, promotion): a calling service with
     * {@code payments:credit.grant}. Idempotent per key.
     */
    CreditEntry grant(GrantCommand command);

    /**
     * Staff grant or correction of a customer's credit with a reason. Within the staff-set limit it posts at once;
     * above it, it waits for a different approver (ledger:adjust to propose).
     */
    CreditAdjustment adjust(AdjustCommand command);

    /** Posts a proposed adjustment; the approver is never the proposer (ledger:approve). */
    CreditAdjustment approve(UUID id, String note);

    /** Rejects a proposed adjustment with the reason (ledger:approve). */
    CreditAdjustment reject(UUID id, String note);

    /** Newest first; null filters for all (payments:read). */
    List<CreditAdjustment> adjustments(AdjustmentStatus status, String customerId, int limit);

    /** What finance owes in credit and in unredeemed gift cards, apart (payments:read). */
    Liabilities liabilities();

    record Balance(String customerId, Money balance) {
    }

    record CustomerCredit(Balance balance, List<CreditEntry> entries, List<CreditAdjustment> adjustments) {
    }

    /** @param source REFERRAL, MEMBERSHIP or PROMO */
    record GrantCommand(String customerId, Money amount, CreditSource source, String reason, String orderId,
                        String reference, String idempotencyKey) {
    }

    /** @param amount positive grants, negative takes credit back; @param source STAFF or PROMO */
    record AdjustCommand(String customerId, Money amount, CreditSource source, String reason) {
    }

    /**
     * Credit and gift card liabilities. {@code flowsMinor} sums the ledger by KIND/SOURCE (grants by source, spends,
     * refunds). {@code ledger*} are the general ledger accounts 2600 and 2650: credit held on open orders is 2600
     * minus the customer balances (spent by the customer, not yet shipped, so not yet revenue).
     */
    record Liabilities(String currency, long creditOutstandingMinor, long accountsWithCredit,
                       Map<String, Long> flowsMinor, long giftCardsOutstandingMinor, long giftCardsOutstandingCount,
                       long giftCardsRedeemedMinor, long giftCardsVoidedMinor, long ledgerCreditMinor,
                       long ledgerGiftCardsMinor, long creditOnOpenOrdersMinor) {
    }
}
