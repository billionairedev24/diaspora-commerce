# ADR 0003 — Bulk product upload is parsed and imported by catalog-service with Apache POI

- **Status:** Accepted
- **Date:** 2026-10-12
- **Deciders:** catalog / Seller Center owners
- **Related:** S307 (one-step product form), S308 (bulk upload); `java/services/catalog` (`domain/imports`,
  `adapter/out/spreadsheet`, `ImportJobImpl`), `go/services/media` (`POST /v1/assets/import`),
  `go/services/gateway-bff` (`SellerImportService`)

## Context

Sellers need to add or update hundreds of products at once from Excel or CSV. The files must get the same checks as
the one-step form: required details per product type, enums, units, GTIN check digits, unique SKUs, image rules. A
partly valid file should still import its good rows, and the import must survive restarts without creating
duplicates.

## Decision

1. **catalog-service owns templates, parsing, validation and the import job.** The product-type schema and the
   listing rules (`DefaultProductDraftPolicy`) live there, so the spreadsheet rules (`ImportValidator`) reuse them and
   cannot drift from the form. gateway-bff only relays the multipart upload and the downloads, with the member's
   token. Seller Center only checks the file name and size before uploading.
2. **Apache POI 5.4.1 (`poi-ooxml`)** reads and writes `.xlsx`.
   - Reading streams the sheet with `XSSFReader` + `XSSFSheetXMLHandler` (SAX), so a 5,000-row file never builds a
     DOM workbook.
   - Writing uses `XSSFWorkbook`, which supports data validations (dropdowns through named ranges on a hidden
     `Lists` sheet), cell comments on the headers, frozen panes and text-formatted columns, so GTINs keep their
     leading zeros.
   - Old `.xls` (OLE2) files are refused with "save as .xlsx".
   - CSV uses our own RFC 4180 codec: comma or semicolon, UTF-8 with or without a BOM, and a windows-1252 fallback.

   Alternatives considered:
   - **FastExcel**: no data validations or comments when writing.
   - **EasyExcel**: heavier, and adds Alibaba's reflection-based model.
   - **Parsing in the BFF (Go, excelize)**: it would duplicate the catalog rules.
3. **Limits** (`ImportLimits`):
   - 10 MB per file (multipart limit 10 MB, request 11 MB) and 5,000 data rows;
   - 200 columns and 5,000 characters per cell;
   - 10 images and 200 variants per product.

   **Zip-bomb guard:** `ZipSecureFile` gets a minimum inflate ratio of 0.01 and a 200 MB maximum entry size, and
   the unzipped size is capped. A file over a limit is refused as a whole (`422 catalog.upload-rejected`).
4. **Formula injection.** Cells that start with `=`, `+`, `-` or `@` are written to templates and error reports with a
   leading `'` (`CellSafety`). Uploaded values are only ever treated as text.
5. **Two phases.**
   - *Upload.* The file is validated and every row is stored (`product_import_row`: cells, messages, group).
     Sellers see the preview and can download an error report: their columns plus `Row status` and `Errors`.
   - *Start.* The background job (`ImportJobImpl`, virtual-thread executor) imports one product group at a time:
     a parent SKU's variants, or a single row. It copies images through media first, outside the transaction. Each
     product is written in its own savepoint, and its rows are marked `CREATED`, `UPDATED` or `FAILED`.
   - *Idempotency.* Rows are unique per `(import_id, sku)`, and a group already done is never redone. A crashed
     import stays `RUNNING`, and `ImportResumeJob` picks it up again after 2 minutes.
   - `validOnly` imports only the ready rows. A product with one bad variant is held back as a whole.
6. **Images.** Rows give public https URLs. catalog calls media `POST /v1/assets/import` with its own
   client-credentials token (`catalog-service`, scope `media:import`). Media checks every dialled address against
   private ranges (SSRF guard), allows https only and at most three redirects, sniffs the type, enforces the upload
   size limit, then quarantines and moderates the image like a seller upload. A rejected image fails its product,
   with the reason in the row's note.
7. **Completion.** `catalog.import.completed` is published on `at.product-import.events`. notifications emails and
   pushes the uploader when the import had 100 rows or more (`IMPORT_NOTIFY_MIN_ROWS`).

## Consequences

- One rule set for the form and the file, tested once (`ImportValidatorTest`, `SpreadsheetFilesTest`,
  `ProductImportIT`).
- catalog gains POI (about 12 MB with its dependencies) and pins `commons-io` 2.18.0 for dependency convergence.
- Images must be at public https URLs. Uploading a ZIP of images with the sheet is not supported yet.
- Image reachability is checked when the import runs, not during the preview: a dead link shows up as a `FAILED` row
  in the report.
