package com.afrotransact.catalog.domain.imports;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingOwner;

/**
 * A bulk upload: the file's validation counts and, once started, the import job's progress. Rows are stored with
 * it, so the preview, the error report and a resumed job all read the same data.
 *
 * @param reference      short reference shown to sellers (BU-000123)
 * @param productTypeCode the template's product type; null for the generic template
 * @param header         the uploaded header cells, in order
 * @param validOnly      started with "import valid rows only": rows with errors are skipped
 * @param productCount   products in the file (rows sharing a Parent SKU count once)
 * @param processedRows  rows the job has finished (created, updated, failed or skipped)
 * @param notifySeller   the seller is notified when it completes (large uploads)
 * @param heartbeatAt    last progress of the job; a running import without progress is resumed by another pod
 */
public record ProductImport(UUID id, String reference, ListingOwner owner, String uploadedBy, String uploadedByName,
                            String fileName, SpreadsheetFormat format, ImportMode mode, String productTypeCode,
                            List<String> header, List<String> fileWarnings, ImportStatus status, boolean validOnly,
                            int totalRows, int validRows, int warningRows, int errorRows, int productCount,
                            int processedRows, int createdProducts, int updatedProducts, int failedRows,
                            boolean notifySeller, Instant createdAt, Instant startedAt, Instant finishedAt,
                            Instant heartbeatAt, long version) {

    public ProductImport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(status, "status");
        header = List.copyOf(header);
        fileWarnings = List.copyOf(fileWarnings);
    }

    /** Rows the job will import: valid and warning rows. */
    public int importableRows() {
        return validRows + warningRows;
    }

    /** Starts the import; refused while rows have errors unless the seller chose to import the valid rows only. */
    public ProductImport start(boolean onlyValid, Instant now) {
        if (status != ImportStatus.VALIDATED) {
            throw new IllegalStateException("This upload was already imported");
        }
        if (errorRows > 0 && !onlyValid) {
            throw new IllegalStateException("Fix the rows with errors and upload again, or import the valid rows only");
        }
        if (importableRows() == 0) {
            throw new IllegalStateException("No row can be imported: every row has errors");
        }
        return with(ImportStatus.QUEUED, onlyValid, processedRows, createdProducts, updatedProducts, failedRows, now,
                null, now);
    }

    public ProductImport running(Instant now) {
        return with(ImportStatus.RUNNING, validOnly, processedRows, createdProducts, updatedProducts, failedRows,
                startedAt == null ? now : startedAt, null, now);
    }

    /** One product (its rows) finished. */
    public ProductImport progressed(int rows, int created, int updated, int failed, Instant now) {
        return with(status, validOnly, processedRows + rows, createdProducts + created, updatedProducts + updated,
                failedRows + failed, startedAt, null, now);
    }

    public ProductImport completed(Instant now) {
        return with(ImportStatus.COMPLETED, validOnly, processedRows, createdProducts, updatedProducts, failedRows,
                startedAt, now, now);
    }

    public ProductImport failed(Instant now) {
        return with(ImportStatus.FAILED, validOnly, processedRows, createdProducts, updatedProducts, failedRows,
                startedAt, now, now);
    }

    private ProductImport with(ImportStatus s, boolean only, int processed, int created, int updated, int failed,
                               Instant started, Instant finished, Instant heartbeat) {
        return new ProductImport(id, reference, owner, uploadedBy, uploadedByName, fileName, format, mode,
                productTypeCode, header, fileWarnings, s, only, totalRows, validRows, warningRows, errorRows,
                productCount, processed, created, updated, failed, notifySeller, createdAt, started, finished, heartbeat,
                version);
    }
}
