package com.afrotransact.payments.domain;

import java.util.Objects;

/**
 * One side of a journal: a strictly positive amount debited or credited to an account.
 *
 * @param orgId   seller / provider the movement is attributed to (statements), or null for platform-only lines
 * @param orderId order this line is attributed to when it differs from its journal's (a label recovery moves money
 *                from an order's payable to the organisation's unattributed label balance); null means the
 *                journal's order
 */
public record JournalLine(LedgerAccount account, EntrySide side, Money amount, String orgId, String orderId) {

    public JournalLine {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Journal line amounts must be positive");
        }
        orgId = orgId == null || orgId.isBlank() ? null : orgId;
        orderId = orderId == null || orderId.isBlank() ? null : orderId;
    }

    public JournalLine(LedgerAccount account, EntrySide side, Money amount, String orgId) {
        this(account, side, amount, orgId, null);
    }

    /** Debit-positive signed amount. */
    public long signedMinor() {
        return side == EntrySide.DEBIT ? amount.minor() : -amount.minor();
    }
}
