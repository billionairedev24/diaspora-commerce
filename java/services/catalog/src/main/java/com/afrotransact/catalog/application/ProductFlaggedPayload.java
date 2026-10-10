package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductFlag;

/**
 * Payload of {@code catalog.product.flagged}: the listing (same identifying fields as CatalogProductEvent) and the
 * report or flag. trust-ops-service queues it as a live-listing moderation item.
 *
 * @param source     CUSTOMER_REPORT, STAFF or AUTOMATED
 * @param reporterId token subject of whoever raised it (customer, staff member or service client)
 * @param riskLevel  optional risk level of an automated flag
 */
public record ProductFlaggedPayload(UUID flagId, UUID productId, String atin, String ownerKind, String ownerOrgId,
                                    String status, String title, UUID categoryId, String productTypeCode,
                                    boolean sellerHidden, String source, String reasonCode, String details,
                                    String reporterId, String riskLevel, Instant occurredAt) {

    static ProductFlaggedPayload of(Product p, ProductFlag f, boolean sellerHidden) {
        return new ProductFlaggedPayload(f.id(), p.id(), p.atin().value(), p.owner().kind().name(),
                p.owner().orgId(), p.status().name(), p.title(), p.categoryId(), p.productTypeCode(), sellerHidden,
                f.source().name(), f.reasonCode().name(), f.details(), f.reporterId(), f.riskLevel(), f.createdAt());
    }
}
