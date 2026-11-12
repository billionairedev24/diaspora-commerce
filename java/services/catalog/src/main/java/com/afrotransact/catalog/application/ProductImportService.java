package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowImage;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.StoredRow;

/**
 * Bulk product upload (S308): templates generated from the product types, upload and row validation, preview, error
 * report, and a background import that is resumable and idempotent per row (key = upload id + row SKU). Members of
 * the uploading organisation with catalog:write; AfroTransact Retail staff upload for the platform.
 */
public interface ProductImportService {

    /**
     * The upload template: a product type's (columns for its attributes and variations; {@code productType} or the
     * type of {@code categoryId}) or the generic one when both are null.
     */
    FileContent template(String productType, UUID categoryId, SpreadsheetFormat format);

    /**
     * The template of an upload mode. OFFER (sell products already on AfroTransact) and PRICE_QTY (price and quantity feed)
     * are generated here with no image, title or attribute columns; CREATE and UPDATE are the product templates above.
     */
    FileContent template(ImportMode mode, String productType, UUID categoryId, SpreadsheetFormat format);

    /**
     * Reads and validates an uploaded .xlsx or .csv file and stores it with its rows (status VALIDATED). Unreadable
     * files (format, size, row limit, missing required columns) are refused with a 422 and nothing is stored.
     *
     * @param productType the template's product type; null when the generic template was used
     */
    ProductImport upload(String fileName, byte[] content, ImportMode mode, String productType, String uploadedByName);

    /** The organisation's uploads, newest first. */
    PageView<ProductImport> list(int page, int size);

    ProductImport get(UUID id);

    /** Rows of an upload in file order, optionally one status (preview filter) or outcome. */
    PageView<StoredRow> rows(UUID id, RowStatus status, RowOutcome outcome, int page, int size);

    /**
     * The images of these rows as the preview shows them (S310), by row number: image cell links and library file
     * names, or the library images named after the row's SKU or Parent SKU, resolved against the image library now.
     */
    Map<Integer, List<RowImage>> rowImages(UUID id, List<StoredRow> rows);

    /**
     * The uploaded rows first, then Row, Row status, Result, ATIN, Severity, Column and Message, formula-injection safe.
     * CSV and XLSX; errors and warnings are told apart by Severity.
     */
    FileContent report(UUID id, SpreadsheetFormat format);

    /**
     * Starts the import in the background. With rows in error the seller must choose {@code validOnly} (those rows
     * are skipped); otherwise 409. Starting again returns the running import.
     */
    ProductImport start(UUID id, boolean validOnly);

    /** Resumes queued or running imports that stopped making progress (crashed or restarted pod). */
    int resumeStalled();

    /** A generated file. */
    record FileContent(String fileName, String contentType, byte[] content) {
    }
}
