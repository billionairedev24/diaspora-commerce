package com.afrotransact.taxreturns.domain.tax;

/** Where one month's tax for one jurisdiction stands. */
public enum FilingStatus {
    /** Net liability is zero or negative and nothing was remitted. */
    NOTHING_DUE,
    /** Tax is due and no remittance is recorded. */
    NOT_FILED,
    /** Remitted exactly what the ledger now says is due. */
    SETTLED,
    /** Remitted less than the ledger now says is due (for example a sale was recorded late). */
    OWES_MORE,
    /** Remitted more than the ledger now says is due (for example a refund came after filing). */
    OVERPAID
}
