package com.afrotransact.inventory.application;

import java.util.UUID;

import com.afrotransact.inventory.domain.StockImport;
import com.afrotransact.inventory.domain.StockImportMode;
import com.afrotransact.inventory.domain.StockImportRow;
import com.afrotransact.inventory.domain.StockRowOutcome;
import com.afrotransact.inventory.port.Page;

/**
 * Stock file import (S395): a seller uploads a CSV of counts for its own stock, sees every row validated, and starts a
 * background import that writes ADJUST movements with the reason BULK_COUNT and the import id. Sellers need
 * {@code inventory:inbound} to upload and {@code inventory:read} to read; they only ever see and change their own stock.
 * Staff with the same permissions name the organisation. AfroTransact Retail's own stock is never changed by a file.
 */
public interface StockImportService {

    /** The template CSV of a mode: instructions as comment lines, the header and one example row. */
    FileContent template(StockImportMode mode);

    /**
     * Reads and validates a CSV and stores it with its rows (status VALIDATED). A file that cannot be read (size, rows,
     * missing columns) is refused with 422 and nothing is stored. 409 while the organisation has an import running.
     *
     * @param requestedOrg staff only: the seller organisation the file is for; sellers always act for their own
     */
    StockImport upload(String fileName, byte[] content, StockImportMode mode, String requestedOrg);

    Page<StockImport> list(String requestedOrg, int page, int size);

    StockImport get(UUID id);

    /** Rows in file order, optionally one status (ERROR for the preview filter) or one outcome. */
    Page<StockImportRow> rows(UUID id, String status, StockRowOutcome outcome, int page, int size);

    /** The uploaded rows first, then Row, Result, Severity, Column, Message, Before and After: CSV, formula-safe. */
    FileContent report(UUID id);

    /**
     * Starts the import in the background. With rows in error the caller must choose {@code validOnly}. A DELTA file that
     * was already applied is refused (409 {@code inventory.import-already-applied}): a change is never applied twice.
     */
    StockImport start(UUID id, boolean validOnly);

    /** Resumes queued or running imports that stopped making progress (crashed or restarted pod). */
    int resumeStalled();

    /** A generated file. */
    record FileContent(String fileName, String contentType, byte[] content) {
    }
}
