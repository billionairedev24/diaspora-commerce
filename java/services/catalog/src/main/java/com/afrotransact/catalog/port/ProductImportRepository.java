package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.domain.imports.ValidatedRow;

/** Bulk uploads and their rows (optimistic locking on the upload's {@code version}). */
public interface ProductImportRepository {

    void insert(ProductImport upload, List<ValidatedRow> rows);

    Optional<ProductImport> findById(UUID id);

    /** Reads and row-locks the upload for the rest of the transaction (one product at a time per upload). */
    Optional<ProductImport> lock(UUID id);

    /** Saves the upload if its version still matches; returns it with version + 1. */
    ProductImport update(ProductImport upload);

    /** The organisation's uploads, newest first. */
    Page<ProductImport> list(String orgId, int page, int size);

    /** Rows in row order, optionally only one status or outcome. */
    Page<StoredRow> rows(UUID importId, RowStatus status, RowOutcome outcome, int page, int size);

    List<StoredRow> allRows(UUID importId);

    /** Group keys (products) that still have pending importable rows, in file order. */
    List<String> pendingGroups(UUID importId, int limit);

    List<StoredRow> group(UUID importId, String groupKey);

    void setOutcome(UUID importId, Collection<Integer> rowNumbers, RowOutcome outcome, String note, UUID productId);

    /** Marks the rows with errors as skipped; returns how many. */
    int skipErrorRows(UUID importId);

    /** Queued or running uploads whose last progress is older than {@code before}, oldest first. */
    List<UUID> stalled(Instant before, int limit);

    String nextReference();

    /** One page of results. */
    record Page<T>(List<T> items, long total, int page, int size) {
    }
}
