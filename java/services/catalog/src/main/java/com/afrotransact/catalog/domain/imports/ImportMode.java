package com.afrotransact.catalog.domain.imports;

/** What a bulk upload does with its rows. */
public enum ImportMode {
    /** Every row is a new product (or a variant of a new product); SKUs already listed are errors. */
    CREATE,
    /** Every row updates the existing variant with that SKU; blank cells leave a value unchanged. */
    UPDATE
}
