package com.afrotransact.payments.domain;

/** Account classes of the chart. Assets and expenses are debit-normal; liabilities and revenue credit-normal. */
public enum AccountType {
    ASSET(EntrySide.DEBIT),
    LIABILITY(EntrySide.CREDIT),
    REVENUE(EntrySide.CREDIT),
    EXPENSE(EntrySide.DEBIT);

    private final EntrySide normalSide;

    AccountType(EntrySide normalSide) {
        this.normalSide = normalSide;
    }

    public EntrySide normalSide() {
        return normalSide;
    }
}
