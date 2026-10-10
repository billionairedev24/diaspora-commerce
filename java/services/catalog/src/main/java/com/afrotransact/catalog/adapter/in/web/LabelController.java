package com.afrotransact.catalog.adapter.in.web;

import java.util.Locale;

import com.afrotransact.catalog.adapter.in.web.LabelDtos.LabelBatchRequest;
import com.afrotransact.catalog.adapter.in.web.LabelDtos.LabelRequest;
import com.afrotransact.catalog.application.LabelService;
import com.afrotransact.catalog.application.RenderedLabel;
import com.afrotransact.catalog.domain.barcode.LabelFormat;
import com.afrotransact.catalog.domain.barcode.SheetLayout;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Labels", description = "Barcode labels (S032): Code 128, GS1-128 and EAN-13 as SVG, PNG, ZPL or PDF")
@RestController
@RequestMapping("/v1/labels")
class LabelController {

    private static final String FORMATS = "SVG (vector label), PNG (bare symbol, no text), ZPL (Zebra, 203 dpi), "
            + "PDF (one page per label, or a sheet)";

    private final LabelService labels;

    LabelController(LabelService labels) {
        this.labels = labels;
    }

    @Operation(summary = "Render one label",
            description = "Needs catalog:write or catalog:read. Invalid data (GTIN / SSCC check digits, GS1 AI rules, "
                    + "too long for the label) is a 422 with fieldErrors.")
    @ApiResponse(responseCode = "200", description = "The label", content = {
        @Content(mediaType = "image/svg+xml", schema = @Schema(type = "string")),
        @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
        @Content(mediaType = "text/plain", schema = @Schema(type = "string", description = "ZPL")),
        @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))})
    @PostMapping
    ResponseEntity<byte[]> renderLabel(@Valid @RequestBody LabelRequest body,
                                  @Parameter(description = FORMATS, schema = @Schema(allowableValues = {"SVG", "PNG",
                                      "ZPL", "PDF"}, defaultValue = "SVG")) @RequestParam(defaultValue = "SVG") String format,
                                  @RequestParam(required = false) SheetLayout.Mode layout,
                                  @RequestParam(required = false) SheetLayout.Paper paper) {
        return response(labels.render(body.toDraft(), format(format), new SheetLayout(layout, paper)));
    }

    @Operation(summary = "Render a batch of labels as one PDF or ZPL",
            description = "Up to 500 labels and 2000 printed labels (copies included). Field errors are keyed "
                    + "labels[i].field. Needs catalog:write or catalog:read.")
    @ApiResponse(responseCode = "200", description = "The labels", content = {
        @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
        @Content(mediaType = "text/plain", schema = @Schema(type = "string", description = "ZPL"))})
    @PostMapping("/batch")
    ResponseEntity<byte[]> renderLabelBatch(@Valid @RequestBody LabelBatchRequest body,
                                 @Parameter(schema = @Schema(allowableValues = {"PDF", "ZPL"}, defaultValue = "PDF"))
                                 @RequestParam(defaultValue = "PDF") String format) {
        return response(labels.renderBatch(body.labels().stream().map(LabelRequest::toDraft).toList(),
                format(format), new SheetLayout(body.layout(), body.paper())));
    }

    @Operation(summary = "Unit label of a product or variant ATIN",
            description = "Platform (AfroTransact Retail) stock with a vendor GTIN keeps the manufacturer barcode; "
                    + "every other unit gets the stock owner's AT-FN code (ATF + 7, Code 128), issued once per owner "
                    + "and variant. Sellers print for their own organisation; staff and service clients may pass "
                    + "orgId (default: the product's owner). Needs catalog:write.")
    @ApiResponse(responseCode = "200", description = "The unit label", content = {
        @Content(mediaType = "image/svg+xml", schema = @Schema(type = "string")),
        @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
        @Content(mediaType = "text/plain", schema = @Schema(type = "string", description = "ZPL")),
        @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))})
    @GetMapping("/units/{atin}")
    ResponseEntity<byte[]> unitLabel(@PathVariable String atin,
                                @Parameter(description = FORMATS, schema = @Schema(allowableValues = {"SVG", "PNG",
                                    "ZPL", "PDF"}, defaultValue = "SVG")) @RequestParam(defaultValue = "SVG") String format,
                                @RequestParam(defaultValue = "1") int copies,
                                @RequestParam(required = false) String orgId) {
        return response(labels.unitLabel(atin, orgId, format(format), copies));
    }

    private static LabelFormat format(String raw) {
        try {
            return LabelFormat.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown label format " + raw + " (SVG, PNG, ZPL or PDF)", e);
        }
    }

    private static ResponseEntity<byte[]> response(RenderedLabel r) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(r.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(r.fileName()).build()
                        .toString())
                .header("X-Label-Count", String.valueOf(r.labels()))
                .body(r.content());
    }
}
