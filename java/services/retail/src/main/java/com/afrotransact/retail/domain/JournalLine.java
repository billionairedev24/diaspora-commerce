package com.afrotransact.retail.domain;

import java.util.Objects;

/** One side of a journal entry; exactly one of debit or credit is positive. */
public record JournalLine(GlAccount account, long debitMinor, long creditMinor) {

    public JournalLine {
        Objects.requireNonNull(account, "account");
        if (debitMinor < 0 || creditMinor < 0 || (debitMinor > 0 && creditMinor > 0)) {
            throw new IllegalArgumentException("A journal line is either a debit or a credit, never negative");
        }
    }

    public static JournalLine debit(GlAccount account, long amountMinor) {
        return amountMinor >= 0 ? new JournalLine(account, amountMinor, 0) : new JournalLine(account, 0, -amountMinor);
    }

    public static JournalLine credit(GlAccount account, long amountMinor) {
        return amountMinor >= 0 ? new JournalLine(account, 0, amountMinor) : new JournalLine(account, -amountMinor, 0);
    }

    public boolean zero() {
        return debitMinor == 0 && creditMinor == 0;
    }
}
