package com.afrotransact.catalog.domain.imports;

import java.util.UUID;

import com.afrotransact.catalog.domain.rules.CategoryRules;

/**
 * A product-type category an upload row can name (the Category column).
 *
 * @param taxStatus the tax gate of the category (ACTIVE, NEEDS_APPROVAL ...); null when it has none
 * @param rules     the listing rules of the category (which fields are required, optional, hidden); null only in
 *                  fixtures, the services always supply them
 */
public record ImportCategory(UUID id, String path, String productTypeCode, String taxStatus,
                             CategoryRules rules) {

    public ImportCategory(UUID id, String path, String productTypeCode, String taxStatus) {
        this(id, path, productTypeCode, taxStatus, null);
    }

    public ImportCategory(UUID id, String path, String productTypeCode) {
        this(id, path, productTypeCode, null, null);
    }

    /** The same category with the listing rules that decide its columns and checks. */
    public ImportCategory withRules(CategoryRules next) {
        return new ImportCategory(id, path, productTypeCode, taxStatus, next);
    }
}
