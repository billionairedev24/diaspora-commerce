package com.afrotransact.catalog.domain.imports;

import java.util.UUID;

/** A product-type category an upload row can name (the Category column). */
public record ImportCategory(UUID id, String path, String productTypeCode) {
}
