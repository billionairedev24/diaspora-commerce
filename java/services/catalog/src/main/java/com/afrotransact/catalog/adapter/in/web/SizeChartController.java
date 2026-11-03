package com.afrotransact.catalog.adapter.in.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.ChartPageResponse;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.ChartRequest;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.ChartResponse;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.DuplicateRequest;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.ImportReportResponse;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.SuggestionsResponse;
import com.afrotransact.catalog.application.PageView;
import com.afrotransact.catalog.application.SizeChartService;
import com.afrotransact.catalog.application.SizeChartService.ChartView;
import com.afrotransact.catalog.application.SizeChartService.FileContent;
import com.afrotransact.catalog.domain.imports.ImportLimits;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Size charts", description = "Size charts sellers write and reuse on their sized products (fashion). "
        + "Sellers create, edit, duplicate and retire their own charts; staff can read but not change them. "
        + "Staff only mark which product types are sized (the sizing trait of /v1/taxonomy/product-types).")
@RestController
@RequestMapping("/v1/size-charts")
class SizeChartController {

    private final SizeChartService charts;

    SizeChartController(SizeChartService charts) {
        this.charts = charts;
    }

    @Operation(operationId = "listSizeCharts", summary = "The organisation's size charts, newest change first",
            description = "Sellers see their own organisation's charts. productTypeId (a product-type category node) "
                    + "or productType (its code) keeps the charts made for that type and the ones made for any. "
                    + "Staff may pass ownerOrgId to read one organisation's charts; without it they read every "
                    + "organisation's (platform-wide staff only). Retired charts are left out unless "
                    + "includeRetired.")
    @GetMapping
    ChartPageResponse list(@RequestParam(required = false) String ownerOrgId,
                           @RequestParam(required = false) UUID productTypeId,
                           @RequestParam(required = false) String productType,
                           @RequestParam(defaultValue = "false") boolean includeRetired,
                           @RequestParam(name = "q", required = false) String text,
                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        PageView<ChartView> result = charts.list(ownerOrgId, productType, productTypeId, includeRetired, text, page,
                size);
        return new ChartPageResponse(result.items().stream().map(ChartResponse::of).toList(), result.total(),
                result.page(), result.size());
    }

    @Operation(operationId = "createSizeChart", summary = "Write a new size chart",
            description = "422 with fieldErrors such as rows[2].values.bust, columns[0].label or name when the chart "
                    + "breaks a rule; the name must differ (ignoring case) from the organisation's other active charts.")
    @PostMapping
    ResponseEntity<ChartResponse> create(@Valid @RequestBody ChartRequest body) {
        ChartView created = charts.create(body.toContent());
        return ResponseEntity.created(URI.create("/v1/size-charts/" + created.chart().id()))
                .body(ChartResponse.of(created));
    }

    @Operation(operationId = "getSizeChartSuggestions", summary = "What a product type suggests for a size chart",
            description = "The sizes (XS to 3XL ...) and the measurement columns (bust, waist, hip ...) staff configured "
                    + "in the product type's sizing trait, to pre-fill the chart editor and offer as size chips. "
                    + "Sellers may use, rename or extend them. sized=false for a product type without sizes. "
                    + "Pass productTypeId (a product-type category node) or productType (its code).")
    @GetMapping("/suggestions")
    SuggestionsResponse suggestions(@RequestParam(required = false) UUID productTypeId,
                                    @RequestParam(required = false) String productType) {
        return SuggestionsResponse.of(charts.suggestions(productTypeId, productType));
    }

    @Operation(operationId = "sizeChartImportTemplate", summary = "Download the size chart upload template",
            description = "One row per size and one column per measurement. With productType (or productTypeId, a "
                    + "product-type category node) the columns are that type's suggested measurements (bust, waist, "
                    + "hip ...); without, a generic template. Any extra column a seller adds is a measurement. xlsx has "
                    + "a Size charts sheet, an Instructions and an Example sheet; csv has \"#\" comment lines and the "
                    + "header row.")
    @GetMapping("/import-template")
    ResponseEntity<byte[]> importTemplate(@RequestParam(required = false) UUID productTypeId,
                                          @RequestParam(required = false) String productType,
                                          @RequestParam(defaultValue = "xlsx") String format) {
        return file(charts.template(productType, productTypeId, formatOf(format)));
    }

    @Operation(operationId = "importSizeCharts", summary = "Upload size charts from a .xlsx or .csv file",
            description = "Rows with the same chart name make one chart. A chart whose name matches one of the "
                    + "organisation's active charts is updated (re-uploading the file changes nothing), others are "
                    + "created. Each chart is saved whole or not at all. dryRun=true validates and reports without "
                    + "saving (applied=false). 200 with per-row messages (unknown or duplicate size, a measurement "
                    + "that is not a number, min above max, a missing value ...); 422 catalog.upload-rejected when the "
                    + "file cannot be read at all (format, 10 MB, 5,000 rows, missing Size chart name / Size column, "
                    + "no measurement column). Pass productType / productTypeId to say which type the charts are made "
                    + "for (the template's).")
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportReportResponse importCharts(@RequestPart("file") MultipartFile file,
                                      @RequestParam(required = false) UUID productTypeId,
                                      @RequestParam(required = false) String productType,
                                      @RequestParam(defaultValue = "false") boolean dryRun) {
        if (file.getSize() > ImportLimits.MAX_FILE_BYTES) {
            throw new SpreadsheetRejected("The file is larger than 10 MB; split it into several files");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        String name = file.getOriginalFilename() == null ? "size-charts" : file.getOriginalFilename();
        return ImportReportResponse.of(charts.importCharts(name, content, productType, productTypeId, dryRun));
    }

    @Operation(operationId = "getSizeChart", summary = "One size chart")
    @GetMapping("/{id}")
    ChartResponse get(@PathVariable UUID id) {
        return ChartResponse.of(charts.get(id));
    }

    @Operation(operationId = "updateSizeChart", summary = "Replace a size chart's content",
            description = "Send the version you loaded; 409 catalog.size-chart-changed when it changed since. The "
                    + "products that use the chart keep it, so every size they still sell must keep a row (422 on "
                    + "rows). A retired chart cannot be edited.")
    @PutMapping("/{id}")
    ChartResponse update(@PathVariable UUID id, @Valid @RequestBody ChartRequest body) {
        return ChartResponse.of(charts.update(id, body.version(), body.toContent()));
    }

    @Operation(operationId = "retireSizeChart", summary = "Retire a size chart",
            description = "A chart is never deleted: the products that use it keep showing it, but it can no longer "
                    + "be linked to products and its name is free again. Returns the retired chart.")
    @DeleteMapping("/{id}")
    ChartResponse retire(@PathVariable UUID id) {
        return ChartResponse.of(charts.retire(id));
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

    @Operation(operationId = "duplicateSizeChart", summary = "Copy a size chart under a new name")
    @PostMapping("/{id}/duplicate")
    ResponseEntity<ChartResponse> duplicate(@PathVariable UUID id,
                                            @Valid @RequestBody(required = false) DuplicateRequest body) {
        ChartView copy = charts.duplicate(id, body == null ? null : body.name());
        return ResponseEntity.created(URI.create("/v1/size-charts/" + copy.chart().id()))
                .body(ChartResponse.of(copy));
    }
}
