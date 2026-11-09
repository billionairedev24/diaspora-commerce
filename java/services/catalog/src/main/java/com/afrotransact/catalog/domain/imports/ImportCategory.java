package com.afrotransact.catalog.domain.imports;

import java.util.UUID;

/**
 * A product-type category an upload row can name (the Category column).
 *
 * @param taxStatus the tax gate of the category (ACTIVE, NEEDS_APPROVAL ...); null when it has none
 */
public record ImportCategory(UUID id, String path, String productTypeCode, String taxStatus) {

    public ImportCategory(UUID id, String path, String productTypeCode) {
        this(id, path, productTypeCode, null);
    }
}
