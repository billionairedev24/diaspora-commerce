package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A stock file import (S395): validation counts and, once started, the job's progress. Rows are stored with it, so the
 * preview, the report and a resumed job read the same data.
 *
 * @param reference    short reference shown to sellers (SI-000123)
 * @param fileSha256   SHA-256 of the uploaded file (hex)
 * @param sameFileAs   the organisation's earlier upload of the same file in the same mode, when there is one
 * @param header       the uploaded header cells, in order
 * @param processedRows rows the job has finished (applied, unchanged, failed or skipped)
 * @param heartbeatAt  last progress of the job; a running import without progress is resumed by another pod
 */
public record StockImport(UUID id, String reference, String ownerOrg, String uploadedBy, String fileName,
                          String fileSha256, UUID sameFileAs, StockImportMode mode, List<String> header,
                          StockImportStatus status, int totalRows, int validRows, int warningRows, int errorRows,
                          int processedRows, int appliedRows, int unchangedRows, int failedRows, Instant createdAt,
                          Instant startedAt, Instant finishedAt, Instant heartbeatAt, long version) {

    public StockImport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerOrg, "ownerOrg");
        Objects.requireNonNull(status, "status");
        header = List.copyOf(header);
    }

    /** Rows the job will apply: valid and warning rows. */
    public int importableRows() {
        return validRows + warningRows;
    }

    /** Starts the import. Refused while rows have errors unless the seller chose to import the valid rows only. */
    public StockImport start(boolean validOnly, Instant now) {
        if (status != StockImportStatus.VALIDATED) {
            throw new IllegalStateException("This file was already imported");
        }
        if (errorRows > 0 && !validOnly) {
            throw new IllegalStateException("Fix the rows with errors and upload again, or import the valid rows only");
        }
        if (importableRows() == 0) {
            throw new IllegalStateException("No row can be imported: every row has errors");
        }
        return with(StockImportStatus.QUEUED, processedRows, appliedRows, unchangedRows, failedRows, now, null, now);
    }

    public StockImport running(Instant now) {
        return with(StockImportStatus.RUNNING, processedRows, appliedRows, unchangedRows, failedRows,
                startedAt == null ? now : startedAt, null, now);
    }

    /** Rows finished by the job. */
    public StockImport progressed(int rows, int applied, int unchanged, int failed, Instant now) {
        return with(status, processedRows + rows, appliedRows + applied, unchangedRows + unchanged, failedRows + failed,
                startedAt, null, now);
    }

    /** The exact counts, read from the rows when the import finishes (progress counts in between are approximate). */
    public StockImport recounted(int processed, int applied, int unchanged, int failed) {
        return new StockImport(id, reference, ownerOrg, uploadedBy, fileName, fileSha256, sameFileAs, mode, header,
                status, totalRows, validRows, warningRows, errorRows, processed, applied, unchanged, failed, createdAt,
                startedAt, finishedAt, heartbeatAt, version);
    }

    public StockImport completed(Instant now) {
        return with(StockImportStatus.COMPLETED, processedRows, appliedRows, unchangedRows, failedRows, startedAt, now,
                now);
    }

    private StockImport with(StockImportStatus s, int processed, int applied, int unchanged, int failed, Instant started,
                             Instant finished, Instant heartbeat) {
        return new StockImport(id, reference, ownerOrg, uploadedBy, fileName, fileSha256, sameFileAs, mode, header, s,
                totalRows, validRows, warningRows, errorRows, processed, applied, unchanged, failed, createdAt, started,
                finished, heartbeat, version);
    }
}
