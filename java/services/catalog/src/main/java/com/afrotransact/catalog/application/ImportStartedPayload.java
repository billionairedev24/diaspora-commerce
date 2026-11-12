package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.catalog.domain.imports.ProductImport;

/**
 * Payload of {@code catalog.import.started}: who started which file in which mode, and how many rows it has. No cell
 * content and no personal data beyond the uploader's subject id.
 *
 * @param sameFileAs the earlier upload of the same file, when the organisation made one
 */
public record ImportStartedPayload(UUID importId, String reference, String ownerOrgId, String uploadedBy, String mode,
                                   String fileSha256, UUID sameFileAs, int totalRows, int importableRows, int errorRows,
                                   boolean validOnly, Instant startedAt) {

    static ImportStartedPayload of(ProductImport u) {
        return new ImportStartedPayload(u.id(), u.reference(), u.owner().orgId(), u.uploadedBy(), u.mode().name(),
                u.fileSha256(), u.sameFileAs(), u.totalRows(), u.importableRows(), u.errorRows(), u.validOnly(),
                u.startedAt());
    }
}
