package com.afrotransact.trustops.domain;

import java.util.Objects;
import java.util.UUID;

/** A catalog product waiting for review, as announced by {@code catalog.product.created} (status IN_REVIEW). */
public record ProductSubmission(UUID productId, String atin, String sellerOrgId, String ownerKind, String title,
                                String productTypeCode) {

    public ProductSubmission {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(sellerOrgId, "sellerOrgId");
        title = title == null || title.isBlank() ? "(untitled)" : title.length() > 200 ? title.substring(0, 200) : title;
    }
}
