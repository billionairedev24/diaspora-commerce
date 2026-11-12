package com.afrotransact.catalog.domain.imports;

/** What the import job did with a row. */
public enum RowOutcome {
    PENDING,
    CREATED,
    UPDATED,
    /** The row sold an existing page: an offer was created on it (S393). */
    OFFER_CREATED,
    /** Valid but not live: waiting for the tax decision, a selling approval or a moderation check (S393). Never failed. */
    HELD,
    /** Valid in the file but refused when imported (image could not be fetched, SKU taken meanwhile …). */
    FAILED,
    /** Not imported: the row has errors and the seller imported the valid rows only. */
    SKIPPED
}
