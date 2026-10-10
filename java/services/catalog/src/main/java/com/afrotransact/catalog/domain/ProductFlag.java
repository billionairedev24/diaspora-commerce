package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A report or flag raised against a live listing; published as {@code catalog.product.flagged} for the trust-ops
 * moderation queue.
 *
 * @param reporterId token subject of the reporter (customer, staff member or service client)
 * @param riskLevel  optional risk level for automated flags (e.g. HIGH from AI triage)
 */
public record ProductFlag(UUID id, UUID productId, String ownerOrgId, FlagSource source, ListingReasonCode reasonCode,
                          String details, String reporterId, String riskLevel, Instant createdAt) {

    public static final int DETAILS_MAX = 1000;

    public ProductFlag {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Validates and creates a flag on {@code product}, which must be live (or paused, for staff and services). */
    public static ProductFlag raise(UUID id, Product product, FlagSource source, ListingReasonCode reasonCode,
                                    String details, String reporterId, String riskLevel, Instant now) {
        if (reasonCode == null) {
            throw new CatalogRuleViolation("Pick a reason", Map.of("reasonCode", "Required"));
        }
        String text = details == null || details.isBlank() ? null : details.trim();
        if (text != null && text.length() > DETAILS_MAX) {
            throw new CatalogRuleViolation("Details are too long", Map.of("details", "At most " + DETAILS_MAX
                    + " characters"));
        }
        if (reasonCode == ListingReasonCode.OTHER && text == null) {
            throw new CatalogRuleViolation("Tell us what is wrong", Map.of("details", "Required for OTHER"));
        }
        boolean flaggable = product.status() == ProductStatus.LIVE
                || (source != FlagSource.CUSTOMER_REPORT && product.status() == ProductStatus.PAUSED);
        if (!flaggable) {
            throw new IllegalStateException("Only live listings can be flagged (was " + product.status() + ")");
        }
        String risk = riskLevel == null || riskLevel.isBlank() ? null : riskLevel.trim();
        return new ProductFlag(id, product.id(), product.owner().orgId(), source, reasonCode, text, reporterId, risk,
                now);
    }
}
