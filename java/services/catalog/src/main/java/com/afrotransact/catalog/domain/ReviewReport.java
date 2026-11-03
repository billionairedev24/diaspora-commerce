package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's or the product owner's report of a published review. One report per reporter and review; reports
 * reach trust-ops' moderation queue through {@code catalog.review.reported}.
 *
 * @param reporterId subject of the customer, or of the seller member who reported
 * @param kind       who reported
 * @param details    optional free text
 */
public record ReviewReport(UUID id, UUID reviewId, String reporterId, Reporter kind, ReviewReasonCode reasonCode,
                           String details, Instant createdAt) {

    public static final int MAX_DETAILS = 500;

    /** Who reported. */
    public enum Reporter {
        CUSTOMER,
        SELLER
    }

    public ReviewReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(reporterId, "reporterId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(createdAt, "createdAt");
        details = details == null || details.isBlank() ? null : details.strip();
        if (details != null && details.length() > MAX_DETAILS) {
            throw new CatalogRuleViolation("Details are at most " + MAX_DETAILS + " characters",
                    Map.of("details", "At most " + MAX_DETAILS + " characters"));
        }
    }
}
