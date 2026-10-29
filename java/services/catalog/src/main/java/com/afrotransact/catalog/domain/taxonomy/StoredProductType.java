package com.afrotransact.catalog.domain.taxonomy;

import java.time.Instant;
import java.util.Objects;

import com.afrotransact.catalog.domain.ProductType;

/** A product type as stored: its schema with the optimistic-locking version and when staff last changed it. */
public record StoredProductType(ProductType type, long version, Instant updatedAt) {

    public StoredProductType {
        Objects.requireNonNull(type, "type");
    }
}
