package com.afrotransact.payments.domain;

import java.util.Objects;

/**
 * One side of a journal: a strictly positive amount debited or credited to an account.
 *
 * @param orgId seller / provider the movement is attributed to (statements), or null for platform-only lines
 */
public record JournalLine(LedgerAccount account, EntrySide side, Money amount, String orgId) {

    public JournalLine {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Journal line amounts must be positive");
        }
        orgId = orgId == null || orgId.isBlank() ? null : orgId;
    }

    /** Debit-positive signed amount. */
    public long signedMinor() {
        return side == EntrySide.DEBIT ? amount.minor() : -amount.minor();
    }
}
