package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.catalog.domain.imports.ProductImport;

/**
 * Payload of {@code catalog.import.completed} (S308). notifications-service tells the uploader when {@code notifySeller}
 * is true (large uploads).
 *
 * @param skippedRows rows not imported because they had errors (valid-rows-only imports)
 */
public record ImportCompletedPayload(UUID importId, String reference, String ownerOrgId, String uploadedBy,
                                     String fileName, String mode, int totalRows, int processedRows,
                                     int createdProducts, int updatedProducts, int failedRows, int skippedRows,
                                     boolean notifySeller, Instant finishedAt) {

    static ImportCompletedPayload of(ProductImport u, int skipped) {
        return new ImportCompletedPayload(u.id(), u.reference(), u.owner().orgId(), u.uploadedBy(), u.fileName(),
                u.mode().name(), u.totalRows(), u.processedRows(), u.createdProducts(), u.updatedProducts(),
                u.failedRows(), skipped, u.notifySeller(), u.finishedAt());
    }
}
