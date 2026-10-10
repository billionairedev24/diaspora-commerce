package com.afrotransact.catalog.domain;

import java.util.Objects;

/** The organisation a product belongs to. */
public record ListingOwner(ListingOwnerKind kind, String orgId) {

    public ListingOwner {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(orgId, "orgId");
    }
}
