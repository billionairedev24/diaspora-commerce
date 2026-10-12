package com.afrotransact.catalog.domain.imports;

import java.util.UUID;

/** The organisation's listing that has a given SKU (Update mode). */
public record ExistingListing(UUID productId, String productTypeCode) {
}
