package com.afrotransact.catalog.adapter.in.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.ImportDtos.ImportPage;
import com.afrotransact.catalog.adapter.in.web.ImportDtos.ImportResponse;
import com.afrotransact.catalog.adapter.in.web.ImportDtos.RowPage;
import com.afrotransact.catalog.adapter.in.web.ImportDtos.RowResponse;
import com.afrotransact.catalog.adapter.in.web.ImportDtos.StartRequest;
import com.afrotransact.catalog.application.PageView;
import com.afrotransact.catalog.application.ProductImportService;
import com.afrotransact.catalog.application.ProductImportService.FileContent;
import com.afrotransact.catalog.domain.imports.ImportLimits;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;
import com.afrotransact.catalog.domain.imports.StoredRow;
import io.swagger.v3.oas.annotations.Operation;
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

@Tag(name = "Bulk upload", description = "Upload templates, .xlsx/.csv uploads with row validation, preview, error "
        + "report and the background import (S308). Limits: 10 MB and 5,000 rows per file")
@RestController
@RequestMapping("/v1/imports")
class ImportController {

    private final ProductImportService imports;

    ImportController(ProductImportService imports) {
        this.imports = imports;
    }

    @Operation(operationId = "importTemplate", summary = "Download an upload template",
            description = "Generated from the product type definitions: a product type's template (productType, or "
                    + "the product type of categoryId) or the generic one. xlsx: Products sheet with required columns "
                    + "marked *, a note per column and dropdowns; Instructions, Example and hidden Lists sheets. csv: "
                    + "\"#\" comment lines and the header row.")
    @GetMapping("/template")
    ResponseEntity<byte[]> template(@RequestParam(required = false) String productType,
                                    @RequestParam(required = false) UUID categoryId,
                                    @RequestParam(defaultValue = "xlsx") String format) {
        return file(imports.template(productType, categoryId, formatOf(format)));
    }

    @Operation(operationId = "uploadImport", summary = "Upload and validate a .xlsx or .csv file",
            description = "Every row is validated (required fields, allowed values, numbers and units, GTIN check "
                    + "digit, duplicate SKUs in the file and against the organisation's listings, image URL format). "
                    + "422 catalog.upload-rejected when the file cannot be read (format, 10 MB, 5,000 rows, missing "
                    + "required columns); nothing is stored then. Starts nothing: see POST /v1/imports/{id}/start.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ImportResponse> upload(@RequestPart("file") MultipartFile file,
                                          @RequestParam(defaultValue = "CREATE") ImportMode mode,
                                          @RequestParam(required = false) String productType,
                                          @RequestParam(required = false) String uploadedByName) {
        if (file.getSize() > ImportLimits.MAX_FILE_BYTES) {
            throw new SpreadsheetRejected("The file is larger than 10 MB; split it into several files");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        String name = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        ProductImport u = imports.upload(name, content, mode, productType, uploadedByName);
        return ResponseEntity.status(HttpStatus.CREATED).body(ImportResponse.of(u));
    }

    @Operation(operationId = "listImports", summary = "The organisation's uploads, newest first (upload history)")
    @GetMapping
    ImportPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        PageView<ProductImport> p = imports.list(page, size);
        return new ImportPage(p.items().stream().map(ImportResponse::of).toList(), p.total(), p.page(), p.size());
    }

    @Operation(operationId = "getImport", summary = "An upload with its validation counts and import progress")
    @GetMapping("/{id}")
    ImportResponse get(@PathVariable UUID id) {
        return ImportResponse.of(imports.get(id));
    }

    @Operation(operationId = "importRows", summary = "Rows of an upload (preview)",
            description = "In file order; status filters the preview "
            + "(ERROR = errors only), outcome the import result")
    @GetMapping("/{id}/rows")
    RowPage rows(@PathVariable UUID id, @RequestParam(required = false) RowStatus status,
                 @RequestParam(required = false) RowOutcome outcome, @RequestParam(defaultValue = "0") int page,
                 @RequestParam(defaultValue = "50") int size) {
        PageView<StoredRow> p = imports.rows(id, status, outcome, page, size);
        return new RowPage(p.items().stream().map(RowResponse::of).toList(), p.total(), p.page(), p.size());
    }

    @Operation(operationId = "importReport", summary = "Download the error report",
            description = "The uploaded rows with Row, Row status, "
            + "Result and Errors columns; cells starting with = + - @ are prefixed with ' (formula injection)")
    @GetMapping("/{id}/report")
    ResponseEntity<byte[]> report(@PathVariable UUID id, @RequestParam(defaultValue = "xlsx") String format) {
        return file(imports.report(id, formatOf(format)));
    }

    @Operation(operationId = "startImport", summary = "Start the import",
            description = "Runs in the background (poll GET /v1/imports/{id}); "
            + "resumable and idempotent per row. 409 catalog.import-has-errors when rows have errors and validOnly "
            + "is false; starting again returns the upload as it is.")
    @PostMapping("/{id}/start")
    ResponseEntity<ImportResponse> start(@PathVariable UUID id, @RequestBody(required = false) StartRequest body) {
        ProductImport u = imports.start(id, body != null && body.validOnly());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ImportResponse.of(u));
    }

    private static SpreadsheetFormat formatOf(String format) {
        SpreadsheetFormat f = SpreadsheetFormat.of(format);
        if (f == null) {
            throw new IllegalArgumentException("format must be xlsx or csv");
        }
        return f;
    }

    private static ResponseEntity<byte[]> file(FileContent f) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.fileName())
                        .build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.content());
    }
}
