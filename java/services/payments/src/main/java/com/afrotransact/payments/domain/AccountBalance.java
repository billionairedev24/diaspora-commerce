package com.afrotransact.payments.domain;

/**
 * Balance of one account in one currency.
 *
 * @param debits  sum of debit lines
 * @param credits sum of credit lines
 */
public record AccountBalance(LedgerAccount account, String currency, long debits, long credits) {

    /** Balance on the account's normal side (positive = what a liability owes / an asset holds). */
    public long balance() {
        return account.chart().type().normalSide() == EntrySide.DEBIT ? debits - credits : credits - debits;
    }

    public Money balanceMoney() {
        return Money.of(balance(), currency);
    }
}
