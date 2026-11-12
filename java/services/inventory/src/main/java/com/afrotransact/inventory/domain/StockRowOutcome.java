package com.afrotransact.inventory.domain;

/** What the import job did with a row of a stock file. */
public enum StockRowOutcome {
    PENDING,
    /** The lot's quantity changed and a BULK_COUNT movement was written. */
    APPLIED,
    /** The count equals what is on hand: nothing changed, no movement. */
    UNCHANGED,
    /** Valid in the file but refused when applied (below reserved, lot not found, stock moved to another owner). */
    FAILED,
    /** Not applied: the row had errors and the seller imported the valid rows only. */
    SKIPPED
}
