package com.afrotransact.inventory.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.StockImport;
import com.afrotransact.inventory.domain.StockImportMode;
import com.afrotransact.inventory.domain.StockImportRow;
import com.afrotransact.inventory.domain.StockRowOutcome;

/** Stock file imports and their rows (optimistic locking on the import's {@code version}). */
public interface StockImportRepository {

    void insert(StockImport upload, List<StockImportRow> rows);

    Optional<StockImport> findById(UUID id);

    /** Reads and row-locks the import for the rest of the transaction (one batch at a time per import). */
    Optional<StockImport> lock(UUID id);

    /** Saves the import if its version still matches; returns it with version + 1. */
    StockImport update(StockImport upload);

    /** The organisation's imports, newest first. */
    Page<StockImport> list(String ownerOrg, int page, int size);

    /** Rows in file order, optionally only one status or outcome. */
    Page<StockImportRow> rows(UUID importId, String status, StockRowOutcome outcome, int page, int size);

    List<StockImportRow> allRows(UUID importId);

    /** The first {@code limit} rows still pending that can be applied, in file order. */
    List<StockImportRow> pendingRows(UUID importId, int limit);

    /**
     * Records what the job did with a row, only while it is still pending; returns 0 when another run already finished it
     * (the caller then rolls its stock change back, so a row is never applied twice).
     */
    int setOutcome(UUID importId, int rowNumber, StockRowOutcome outcome, String note, Integer before, Integer after);

    /** Rows of the import per outcome (the final counts). */
    Map<StockRowOutcome, Long> outcomeCounts(UUID importId);

    /** Marks the rows with errors as skipped; returns how many. */
    int skipErrorRows(UUID importId);

    /** The organisation's latest earlier import of the same file in the same mode. */
    Optional<StockImport> findLatestByHash(String ownerOrg, StockImportMode mode, String fileSha256);

    /** Whether the organisation has an import that is queued or running. */
    boolean hasActive(String ownerOrg);

    /** Queued or running imports without progress since {@code before}, oldest first. */
    List<UUID> stalled(Instant before, int limit);

    String nextReference();
}
