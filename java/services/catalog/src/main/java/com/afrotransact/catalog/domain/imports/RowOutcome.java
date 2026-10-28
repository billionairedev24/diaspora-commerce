package com.afrotransact.catalog.domain.imports;

/** What the import job did with a row. */
public enum RowOutcome {
    PENDING,
    CREATED,
    UPDATED,
    /** Valid in the file but refused when imported (image could not be fetched, SKU taken meanwhile …). */
    FAILED,
    /** Not imported: the row has errors and the seller imported the valid rows only. */
    SKIPPED
}
