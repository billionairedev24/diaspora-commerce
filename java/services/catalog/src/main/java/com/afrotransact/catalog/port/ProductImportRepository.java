package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.imports.ImportMode;
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

    /** The first {@code limit} importable rows still pending, in file order (offer and feed uploads: one row each). */
    List<StoredRow> pendingRows(UUID importId, int limit);

    /** Of these row numbers, the ones that are still pending (the job saves only those). */
    Set<Integer> pendingAmong(UUID importId, Collection<Integer> rowNumbers);

    /** Records what the job did with single rows: outcome, note, and the page, offer and ATIN they ended on. */
    void setRowResults(UUID importId, List<RowResult> results);

    /** The organisation's latest earlier upload of the same file (same SHA-256 and mode), if any. */
    Optional<ProductImport> findLatestByHash(String orgId, ImportMode mode, String fileSha256);

    /** Uploads of the organisation created since {@code since} (the daily limit). */
    int countCreatedSince(String orgId, Instant since);

    /** Whether the organisation has an upload of this mode that is queued or running. */
    boolean hasActive(String orgId, ImportMode mode);

    /** One audit row per product or offer the job created, changed or held. */
    void audit(UUID importId, String orgId, String actor, Instant at, List<AuditRow> rows);

    /** Content hashes of the CREATE rows already imported for these SKUs of the organisation, by SKU. */
    Map<String, String> rowHashes(String orgId, Collection<String> skus);

    /** Remembers the content hash of each imported CREATE row (SKU to hash and product). */
    void saveRowHashes(String orgId, UUID importId, Instant at, Map<String, RowHash> hashes);

    /** Marks the rows with errors as skipped; returns how many. */
    int skipErrorRows(UUID importId);

    /** Queued or running uploads whose last progress is older than {@code before}, oldest first. */
    List<UUID> stalled(Instant before, int limit);

    String nextReference();

    /** What the job did with one row. */
    record RowResult(int rowNumber, RowOutcome outcome, String note, String atin, UUID offerId, UUID productId) {
    }

    /** An audit row: {@code action} is CREATED, UPDATED, OFFER_CREATED or HELD. */
    record AuditRow(int rowNumber, String action, String atin, UUID productId, UUID offerId) {
    }

    /** The content hash of an imported CREATE row. */
    record RowHash(String hash, UUID productId) {
    }

    /** One page of results. */
    record Page<T>(List<T> items, long total, int page, int size) {
    }
}
