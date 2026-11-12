package com.afrotransact.inventory.adapter.in.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.application.StockImportService;
import com.afrotransact.inventory.application.StockImportService.FileContent;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.StockImport;
import com.afrotransact.inventory.domain.StockImportMode;
import com.afrotransact.inventory.domain.StockImportRow;
import com.afrotransact.inventory.domain.StockRowMessage;
import com.afrotransact.inventory.domain.StockRowOutcome;
import com.afrotransact.inventory.port.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Stock import", description = "Stock CSV import: counts for the organisation's own stock, validated row by "
        + "row, applied in the background with the reason BULK_COUNT (S395). Up to 50,000 rows and 25 MB")
@RestController
@RequestMapping("/v1/stock/imports")
class StockImportController {

    private static final long MAX_FILE_BYTES = 25L * 1024 * 1024;

    private final StockImportService imports;

    StockImportController(StockImportService imports) {
        this.imports = imports;
    }

    @Operation(operationId = "stockImportTemplate", summary = "Download the stock file template (CSV)",
            description = "mode=ABSOLUTE: the On hand column is what you counted. mode=DELTA: the Change column is added "
                    + "to what is on hand (never applied twice). Needs inventory:inbound.")
    @GetMapping("/template")
    ResponseEntity<byte[]> template(@RequestParam(defaultValue = "ABSOLUTE") StockImportMode mode) {
        return file(imports.template(mode));
    }

    @Operation(operationId = "uploadStockImport", summary = "Upload and validate a stock CSV",
            description = "Every row is validated: SKU held by you at that location, location code, quantity (whole "
                    + "number, never negative, at most 99,999), lot and expiry. A SKU of another organisation looks like an "
                    + "unknown one. 422 inventory.upload-rejected when the file cannot be read (size, rows, missing columns); "
                    + "nothing is stored then. 403 inventory.retail-stock for AfroTransact Retail's own stock. 409 "
                    + "inventory.import-already-running. Starts nothing: see POST /v1/stock/imports/{id}/start.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ImportResponse> upload(@RequestPart("file") MultipartFile file,
                                          @RequestParam(defaultValue = "ABSOLUTE") StockImportMode mode,
                                          @RequestParam(required = false) String ownerOrg) {
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new InventoryRuleViolation("inventory.upload-rejected", "The file is larger than 25 MB; split it into "
                    + "several files");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        String name = file.getOriginalFilename() == null ? "stock.csv" : file.getOriginalFilename();
        return ResponseEntity.status(HttpStatus.CREATED).body(ImportResponse.of(imports.upload(name, content, mode,
                ownerOrg)));
    }

    @Operation(operationId = "listStockImports", summary = "The organisation's stock files, newest first")
    @GetMapping
    ImportPage list(@RequestParam(required = false) String ownerOrg, @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "25") int size) {
        Page<StockImport> p = imports.list(ownerOrg, page, size);
        return new ImportPage(p.items().stream().map(ImportResponse::of).toList(), p.total(), p.page(), p.size());
    }

    @Operation(operationId = "getStockImport", summary = "A stock file with its validation counts and import progress")
    @GetMapping("/{id}")
    ImportResponse get(@PathVariable UUID id) {
        return ImportResponse.of(imports.get(id));
    }

    @Operation(operationId = "stockImportRows", summary = "Rows of a stock file (preview)",
            description = "In file order; status=ERROR filters the preview to the rows with errors, outcome to the import "
                    + "result")
    @GetMapping("/{id}/rows")
    RowPage rows(@PathVariable UUID id, @RequestParam(required = false) String status,
                 @RequestParam(required = false) StockRowOutcome outcome, @RequestParam(defaultValue = "0") int page,
                 @RequestParam(defaultValue = "50") int size) {
        Page<StockImportRow> p = imports.rows(id, status, outcome, page, size);
        return new RowPage(p.items().stream().map(RowResponse::of).toList(), p.total(), p.page(), p.size());
    }

    @Operation(operationId = "stockImportReport", summary = "Download the row report (CSV)",
            description = "The uploaded columns first, then Row, Result, Severity, Column, Message, Before and After. Cells "
                    + "starting with = + - @ are prefixed with ' (formula injection).")
    @GetMapping("/{id}/report")
    ResponseEntity<byte[]> report(@PathVariable UUID id) {
        return file(imports.report(id));
    }

    @Operation(operationId = "startStockImport", summary = "Start the import",
            description = "Runs in the background (poll GET /v1/stock/imports/{id}): each row locks the position and lot, "
                    + "refuses another owner's stock and a count below the reserved units, and writes an ADJUST movement "
                    + "with the reason BULK_COUNT and the import id. 409 inventory.import-has-errors unless validOnly; "
                    + "409 inventory.import-already-applied for a DELTA file already applied. Starting again returns the "
                    + "import as it is.")
    @PostMapping("/{id}/start")
    ResponseEntity<ImportResponse> start(@PathVariable UUID id, @RequestBody(required = false) StartRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ImportResponse.of(imports.start(id, body != null
                && body.validOnly())));
    }

    private static ResponseEntity<byte[]> file(FileContent f) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.fileName())
                        .build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.content());
    }

    @Schema(name = "StockImport", description = "A stock file: validation counts and import progress")
    record ImportResponse(UUID id, String reference, String fileName, String fileSha256,
                          @Schema(description = "The organisation's earlier upload of the same file in the same mode")
                          UUID sameFileAs, @Schema(allowableValues = {"ABSOLUTE", "DELTA"}) String mode,
                          @Schema(allowableValues = {"VALIDATED", "QUEUED", "RUNNING", "COMPLETED", "FAILED"})
                          String status, String ownerOrg, int totalRows, int validRows, int warningRows, int errorRows,
                          int processedRows, int appliedRows, int unchangedRows, int failedRows, List<String> header,
                          String uploadedBy, Instant createdAt, Instant startedAt, Instant finishedAt) {

        static ImportResponse of(StockImport u) {
            return new ImportResponse(u.id(), u.reference(), u.fileName(), u.fileSha256(), u.sameFileAs(),
                    u.mode().name(), u.status().name(), u.ownerOrg(), u.totalRows(), u.validRows(), u.warningRows(),
                    u.errorRows(), u.processedRows(), u.appliedRows(), u.unchangedRows(), u.failedRows(), u.header(),
                    u.uploadedBy(), u.createdAt(), u.startedAt(), u.finishedAt());
        }
    }

    @Schema(name = "StockImportPage")
    record ImportPage(List<ImportResponse> items, long total, int page, int size) {
    }

    @Schema(name = "StockImportRowMessage")
    record MessageDto(String column, @Schema(allowableValues = {"ERROR", "WARNING"}) String severity, String message) {

        static MessageDto of(StockRowMessage m) {
            return new MessageDto(m.column(), m.severity(), m.message());
        }
    }

    @Schema(name = "StockImportRow")
    record RowResponse(int rowNumber, String sku, String location, String lot, Integer quantity, String expiry,
                       @Schema(allowableValues = {"VALID", "WARNING", "ERROR"}) String status,
                       @Schema(allowableValues = {"PENDING", "APPLIED", "UNCHANGED", "FAILED", "SKIPPED"})
                       String outcome, String outcomeNote, Integer before, Integer after, List<MessageDto> messages,
                       List<String> cells) {

        static RowResponse of(StockImportRow r) {
            return new RowResponse(r.rowNumber(), r.sku(), r.nodeId(), r.lotNo(), r.quantity(),
                    r.expiry() == null ? null : r.expiry().toString(), r.status(), r.outcome().name(), r.outcomeNote(),
                    r.before(), r.after(), r.messages().stream().map(MessageDto::of).toList(), r.cells());
        }
    }

    @Schema(name = "StockImportRowPage")
    record RowPage(List<RowResponse> items, long total, int page, int size) {
    }

    @Schema(name = "StartStockImportRequest", description = "validOnly: import the valid and warning rows and skip the "
            + "rows with errors (required when the file has errors)")
    record StartRequest(boolean validOnly) {
    }
}
