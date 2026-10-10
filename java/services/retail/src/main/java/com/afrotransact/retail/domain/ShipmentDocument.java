package com.afrotransact.retail.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A required shipping or customs document and its status (hold notes go in {@code note}). The files themselves (the
 * supplier's ASN / packing list, bill of lading, certificates …) are media assets (S225): {@code assetIds} are media
 * service asset ids, never file contents or URLs.
 */
public record ShipmentDocument(String name, DocumentStatus status, String reference, String note, Instant updatedAt,
                               List<UUID> assetIds) {

    public static final int MAX_ASSETS = 10;

    public ShipmentDocument {
        if (name == null || name.isBlank()) {
            throw new RetailRuleViolation("retail.document-invalid", "Document name is required");
        }
        Objects.requireNonNull(status, "status");
        assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
        if (assetIds.size() > MAX_ASSETS) {
            throw new RetailRuleViolation("retail.document-invalid", "At most " + MAX_ASSETS + " files per document");
        }
    }

    /** A document without files. */
    public ShipmentDocument(String name, DocumentStatus status, String reference, String note, Instant updatedAt) {
        this(name, status, reference, note, updatedAt, List.of());
    }
}
