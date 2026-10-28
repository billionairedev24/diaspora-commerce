package com.afrotransact.payments.application;

import java.time.Instant;

import com.afrotransact.payments.domain.Money;

/**
 * Refunds go back to what paid each part: the credit part as credit, the card part to the card. Only staff, with a
 * reason, may send the card part of an order's refunds to credit instead, inside a support resolution; customers cannot
 * choose. The choice is one way: credit is never turned back into a card refund or cash, and refunds already sent to
 * the card stay there (S320).
 */
public interface CreditRefundService {

    /** Staff choose it for a customer with a reason, kept in the audit trail (ledger:adjust). */
    Choice chooseAsStaff(String orderId, String reason);

    /**
     * @param cardPart what the card paid for the order: the part that later refunds return as credit
     * @param chosenBy the staff member
     */
    record Choice(String orderId, boolean cardRefundsAsCredit, Money cardPart, String chosenBy, Instant chosenAt) {
    }
}
