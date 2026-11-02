package com.afrotransact.catalog.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.SizeChartService.ChartView;
import com.afrotransact.catalog.application.SizeChartService.Suggestions;
import com.afrotransact.catalog.domain.sizing.ChartColumn;
import com.afrotransact.catalog.domain.sizing.ChartContent;
import com.afrotransact.catalog.domain.sizing.ChartRow;
import com.afrotransact.catalog.domain.sizing.FitNote;
import com.afrotransact.catalog.domain.sizing.MeasurementRange;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizeChart;
import com.afrotransact.catalog.domain.sizing.SuggestedMeasurement;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies for /v1/size-charts and the size chart of the public product page. */
final class SizeChartDtos {

    private SizeChartDtos() {
    }

    @Schema(name = "SizeChartColumn", description = "One measurement of a chart (Bust, Foot length ...)")
    record ColumnDto(
            @Schema(description = "Stable key rows refer to; derived from the label when absent")
            @Size(max = 40) String key,
            @NotBlank @Size(max = 40) String label,
            @Schema(description = "Always the chart's unit; may be omitted") MeasurementUnit unit) {

        ChartColumn toDomain() {
            return new ChartColumn(key == null ? "" : key, label, unit);
        }

        static ColumnDto of(ChartColumn c) {
            return new ColumnDto(c.key(), c.label(), c.unit());
        }
    }

    @Schema(name = "SizeChartValue", description = "A single value (max omitted or equal to min) or a range, in the "
            + "chart's unit")
    record ValueDto(@NotNull BigDecimal min, BigDecimal max) {

        MeasurementRange toDomain() {
            return new MeasurementRange(min, max);
        }

        static ValueDto of(MeasurementRange r) {
            return new ValueDto(r.min(), r.max());
        }
    }

    @Schema(name = "SizeChartRowInput", description = "One size: its label and a value per column key")
    record RowRequest(@NotBlank @Size(max = 40) String size, @NotNull Map<String, @Valid ValueDto> values) {

        ChartRow toDomain() {
            Map<String, MeasurementRange> out = new LinkedHashMap<>();
            values.forEach((k, v) -> {
                if (v != null) {
                    out.put(k, v.toDomain());
                }
            });
            return new ChartRow(size, out);
        }
    }

    @Schema(name = "SizeChartRequest", description = "A size chart as the seller writes it. productTypeCode and "
            + "categoryId say what it was made for (both optional; a category implies its product type). The chart's "
            + "unit is the unit of every value; the other unit is shown by conversion in responses.")
    record ChartRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull MeasurementUnit unitSystem,
            @Size(max = 40) String productTypeCode,
            @Schema(description = "A product-type (third level) category node") UUID categoryId,
            @NotEmpty @Size(max = 12) List<@Valid ColumnDto> columns,
            @NotEmpty @Size(max = 60) List<@Valid RowRequest> rows,
            FitNote fitNote,
            @Size(max = 1000) String howToMeasure,
            @Schema(description = "PUT only: the version the editor loaded (409 when the chart changed since)")
            @PositiveOrZero Long version) {

        ChartContent toContent() {
            return new ChartContent(name, unitSystem, productTypeCode, categoryId,
                    columns.stream().map(ColumnDto::toDomain).toList(),
                    rows.stream().map(RowRequest::toDomain).toList(), fitNote, howToMeasure);
        }
    }

    @Schema(name = "DuplicateSizeChartRequest")
    record DuplicateRequest(@Schema(description = "Default: \"<name> (copy)\"") @Size(max = 80) String name) {
    }

    @Schema(name = "SizeChartRow")
    record RowResponse(String size,
                       @Schema(description = "Value per column key, in the chart's unit") Map<String, ValueDto> values,
                       @Schema(description = "The same values converted to alternateUnit, one decimal")
                       Map<String, ValueDto> alternateValues) {

        static RowResponse of(ChartRow row, MeasurementUnit unit) {
            Map<String, ValueDto> own = new LinkedHashMap<>();
            Map<String, ValueDto> other = new LinkedHashMap<>();
            row.values().forEach((k, v) -> {
                own.put(k, ValueDto.of(v));
                other.put(k, ValueDto.of(v.convert(unit, unit.other())));
            });
            return new RowResponse(row.size(), own, other);
        }
    }

    @Schema(name = "SizeChart")
    record ChartResponse(UUID id, String ownerOrgId, String name, MeasurementUnit unitSystem,
                         MeasurementUnit alternateUnit, String productTypeCode, UUID categoryId,
                         List<ColumnDto> columns, List<RowResponse> rows, FitNote fitNote, String howToMeasure,
                         boolean retired, Instant retiredAt,
                         @Schema(description = "Products that use this chart") long productCount,
                         Instant createdAt, Instant updatedAt,
                         @Schema(description = "Send back as version when saving (PUT)") long version) {

        static ChartResponse of(ChartView v) {
            SizeChart c = v.chart();
            return new ChartResponse(c.id(), c.ownerOrg(), c.name(), c.unitSystem(), c.unitSystem().other(),
                    c.productTypeCode(), c.categoryId(), c.columns().stream().map(ColumnDto::of).toList(),
                    c.rows().stream().map(r -> RowResponse.of(r, c.unitSystem())).toList(), c.fitNote(),
                    c.howToMeasure(), c.retired(), c.retiredAt(), v.productCount(), c.createdAt(), c.updatedAt(),
                    c.version());
        }
    }

    @Schema(name = "SizeChartPage")
    record ChartPageResponse(List<ChartResponse> items, long total, int page, int size) {
    }

    @Schema(name = "SizeChartSuggestedMeasurement")
    record MeasurementResponse(String key, String label, MeasurementUnit unit, String help) {

        static MeasurementResponse of(SuggestedMeasurement m) {
            return new MeasurementResponse(m.key(), m.label(), m.unit(), m.help());
        }
    }

    @Schema(name = "SizeChartSuggestions", description = "What the category suggests for the chart editor and the "
            + "size input of the product form: size labels and measurement columns. All of it can be used, renamed "
            + "or extended by the seller.")
    record SuggestionsResponse(String productTypeCode, String productTypeLabel,
                               @Schema(description = "false: this product type has no sizes and no size chart")
                               boolean sized,
                               @Schema(description = "A listing needs a size chart whose rows cover every size it sells")
                               boolean sizeChartRequired,
                               @Schema(description = "The variation dimension that holds the size, e.g. Size")
                               String sizeDimension,
                               @Schema(description = "Suggested size labels, in order (XS ... 3XL)") List<String> sizes,
                               @Schema(description = "The seller may add size labels of their own") boolean
                                       sizesAllowCustomValues,
                               @Schema(description = "The unit to start a new chart in") MeasurementUnit unitSystem,
                               List<MeasurementResponse> measurements) {

        static SuggestionsResponse of(Suggestions s) {
            return new SuggestionsResponse(s.productTypeCode(), s.productTypeLabel(), s.sized(), s.sizeChartRequired(),
                    s.sizeDimension(), s.sizes(), s.sizesAllowCustomValues(), s.unitSystem(),
                    s.measurements().stream().map(MeasurementResponse::of).toList());
        }
    }

    @Schema(name = "PublicSizeChart", description = "The size chart shown on the product page")
    record PublicChartResponse(String name, MeasurementUnit unitSystem, MeasurementUnit alternateUnit,
                               List<ColumnDto> columns, List<RowResponse> rows,
                               @Schema(description = "The product's own fit note, else the chart's") FitNote fitNote,
                               String howToMeasure) {

        static PublicChartResponse of(SizeChart c, FitNote productFit) {
            return new PublicChartResponse(c.name(), c.unitSystem(), c.unitSystem().other(),
                    c.columns().stream().map(ColumnDto::of).toList(),
                    c.rows().stream().map(r -> RowResponse.of(r, c.unitSystem())).toList(),
                    productFit != null ? productFit : c.fitNote(), c.howToMeasure());
        }
    }
}
