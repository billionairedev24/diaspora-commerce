package com.afrotransact.retail.domain;

import java.time.Instant;
import java.util.Objects;

/** A required shipping or customs document and its status (hold notes go in {@code note}). */
public record ShipmentDocument(String name, DocumentStatus status, String reference, String note, Instant updatedAt) {

    public ShipmentDocument {
        if (name == null || name.isBlank()) {
            throw new RetailRuleViolation("retail.document-invalid", "Document name is required");
        }
        Objects.requireNonNull(status, "status");
    }
}
