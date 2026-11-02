package com.afrotransact.catalog.adapter.in.web;

import java.net.URI;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.ChartPageResponse;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.ChartRequest;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.ChartResponse;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.DuplicateRequest;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.SuggestionsResponse;
import com.afrotransact.catalog.application.PageView;
import com.afrotransact.catalog.application.SizeChartService;
import com.afrotransact.catalog.application.SizeChartService.ChartView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @Operation(operationId = "duplicateSizeChart", summary = "Copy a size chart under a new name")
    @PostMapping("/{id}/duplicate")
    ResponseEntity<ChartResponse> duplicate(@PathVariable UUID id,
                                            @Valid @RequestBody(required = false) DuplicateRequest body) {
        ChartView copy = charts.duplicate(id, body == null ? null : body.name());
        return ResponseEntity.created(URI.create("/v1/size-charts/" + copy.chart().id()))
                .body(ChartResponse.of(copy));
    }
}
