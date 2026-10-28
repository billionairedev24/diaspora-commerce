package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowImage;
import com.afrotransact.catalog.domain.imports.RowMessage;
import com.afrotransact.catalog.domain.imports.StoredRow;
import io.swagger.v3.oas.annotations.media.Schema;

/** Request and response bodies for /v1/imports (bulk upload, S308). */
final class ImportDtos {

    private ImportDtos() {
    }

    @Schema(name = "ProductImport", description = "A bulk upload: validation counts and import progress")
    record ImportResponse(UUID id, String reference, String fileName,
                          @Schema(allowableValues = {"XLSX", "CSV"}) String format,
                          @Schema(allowableValues = {"CREATE", "UPDATE"}) String mode, String productType,
                          @Schema(allowableValues = {"VALIDATED", "QUEUED", "RUNNING", "COMPLETED", "FAILED"})
                          String status,
                          boolean validOnly, int totalRows, int validRows, int warningRows, int errorRows,
                          int productCount, int processedRows, int createdProducts, int updatedProducts,
                          int failedRows, List<String> header, List<String> fileWarnings, String uploadedBy,
                          String uploadedByName, boolean notifySeller, Instant createdAt, Instant startedAt,
                          Instant finishedAt) {

        static ImportResponse of(ProductImport u) {
            return new ImportResponse(u.id(), u.reference(), u.fileName(), u.format().name(), u.mode().name(),
                    u.productTypeCode(), u.status().name(), u.validOnly(), u.totalRows(), u.validRows(),
                    u.warningRows(), u.errorRows(), u.productCount(), u.processedRows(), u.createdProducts(),
                    u.updatedProducts(), u.failedRows(), u.header(), u.fileWarnings(), u.uploadedBy(),
                    u.uploadedByName(), u.notifySeller(), u.createdAt(), u.startedAt(), u.finishedAt());
        }
    }

    @Schema(name = "ProductImportPage")
    record ImportPage(List<ImportResponse> items, long total, int page, int size) {
    }

    @Schema(name = "ImportRowMessage", description = "column = the column key (price, attr:material); null for the row")
    record MessageDto(String column, int columnIndex, @Schema(allowableValues = {"ERROR", "WARNING"}) String severity,
                      String message) {

        static MessageDto of(RowMessage m) {
            return new MessageDto(m.column(), m.columnIndex(), m.severity().name(), m.message());
        }
    }

    @Schema(name = "ProductImportRow")
    record RowResponse(int rowNumber, String groupKey, String sku,
                       @Schema(allowableValues = {"VALID", "WARNING", "ERROR"}) String status,
                       @Schema(allowableValues = {"PENDING", "CREATED", "UPDATED", "FAILED", "SKIPPED"})
                       String outcome,
                       String outcomeNote, UUID productId, List<MessageDto> messages, List<String> cells,
                       List<RowImageDto> images) {

        static RowResponse of(StoredRow r, List<RowImage> images) {
            return new RowResponse(r.rowNumber(), r.groupKey(), r.sku(), r.status().name(), r.outcome().name(),
                    r.outcomeNote(), r.productId(), r.messages().stream().map(MessageDto::of).toList(), r.cells(),
                    images == null ? List.of() : images.stream().map(RowImageDto::of).toList());
        }
    }

    @Schema(name = "ImportRowImage", description = "One image of an upload row (S310)")
    record RowImageDto(@Schema(allowableValues = {"MAIN", "PT01", "PT02", "PT03", "PT04", "PT05", "PT06", "PT07",
                               "PT08", "SWCH"}) String slot,
                       @Schema(allowableValues = {"LINK", "FILE", "SKU"}) String source, String ref,
                       @Schema(allowableValues = {"FOUND", "LINK", "HELD", "MISSING", "BAD_LINK"}) String state,
                       String url, Integer width, Integer height, String message) {

        static RowImageDto of(RowImage i) {
            return new RowImageDto(i.slot(), i.source(), i.ref(), i.state(), i.url(), i.width(), i.height(),
                    i.message());
        }
    }

    @Schema(name = "ProductImportRowPage")
    record RowPage(List<RowResponse> items, long total, int page, int size) {
    }

    @Schema(name = "StartImportRequest", description = "validOnly: import the valid and warning rows and skip the "
            + "rows with errors (required when the upload has errors)")
    record StartRequest(boolean validOnly) {
    }
}
