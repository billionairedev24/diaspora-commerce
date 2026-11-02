package com.afrotransact.catalog.domain.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.sizing.FitNote;
import com.afrotransact.catalog.domain.sizing.SizeChart;
import com.afrotransact.catalog.domain.sizing.SizingFixtures;
import org.junit.jupiter.api.Test;

/** The product upload for sized categories: custom sizes and colours, the Size chart and Fit columns. */
class ImportSizingTest {

    private static final ImportCategory DRESSES = new ImportCategory(UUID.fromString(
            "00000000-0000-0000-0000-0000000000d1"), "Fashion › Women › Dresses", "dress");

    private static final List<String> HEADER = List.of("Parent SKU", "SKU *", "Title *", "Brand *", "Image URLs *",
            "Price (USD) *", "Quantity *", "No barcode", "Variation", "Size", "Color", "Size chart", "Fit");

    private static ImportContext ctx(ProductType type, SizeChart... charts) {
        Map<String, SizeChart> byName = new HashMap<>();
        for (SizeChart c : charts) {
            byName.put(c.name().toLowerCase(Locale.ROOT), c);
        }
        return new ImportContext(ImportMode.CREATE, type, Map.of(type.code(), type), List.of(DRESSES), Set.of(),
                Set.of(), Map.of(), ImageLibrary.EMPTY, SizingFixtures.OWNER, byName);
    }

    private static Map<String, String> row(String parent, String sku, String size, String color, String chart) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("Parent SKU", parent);
        r.put("SKU *", sku);
        r.put("Title *", "Ankara Wrap Dress, Puff Sleeve");
        r.put("Brand *", "Adire Lagos");
        r.put("Image URLs *", ImportFixtures.IMG);
        r.put("Price (USD) *", "49.00");
        r.put("Quantity *", "12");
        r.put("No barcode", "Yes");
        r.put("Variation", "Size × Color");
        r.put("Size", size);
        r.put("Color", color);
        r.put("Size chart", chart);
        return r;
    }

    @SafeVarargs
    private static ValidationResult validate(ImportContext ctx, Map<String, String>... rows) {
        List<SheetRow> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            Map<String, String> r = rows[i];
            out.add(new SheetRow(i + 2, HEADER.stream().map(h -> r.getOrDefault(h, "")).toList()));
        }
        return ImportValidator.validate(new ParsedSheet(HEADER, out), ctx);
    }

    private static List<String> errors(ValidatedRow r) {
        return r.messages().stream().filter(m -> m.severity() == Severity.ERROR)
                .map(m -> m.column() + ": " + m.message()).toList();
    }

    @Test
    void theTemplateOfASizedTypeHasTheSizeChartAndFitColumnsAndSuggestedOptions() {
        List<ImportColumn> cols = ImportColumns.forType(SizingFixtures.DRESS, List.of(DRESSES.path()));

        assertThat(cols).extracting(ImportColumn::key).contains("option:Size", "option:Color", "size_chart", "fit_note");
        ImportColumn size = cols.stream().filter(c -> c.key().equals("option:Size")).findFirst().orElseThrow();
        assertThat(size.kind()).isEqualTo(ColumnKind.SUGGESTED);
        assertThat(size.options()).containsExactly("XS", "S", "M", "L", "XL");
        assertThat(size.description()).contains("type your own").contains("UK 12");
        ImportColumn chart = cols.stream().filter(c -> c.key().equals("size_chart")).findFirst().orElseThrow();
        assertThat(chart.required()).isFalse();
        assertThat(ImportColumns.forType(SizingFixtures.DRESS_CHART_REQUIRED, List.of()).stream()
                .filter(c -> c.key().equals("size_chart")).findFirst().orElseThrow().required()).isTrue();
    }

    @Test
    void aTypeWithoutSizingHasNoSizeChartColumnsAndKeepsStrictOptions() {
        List<ImportColumn> cols = ImportColumns.forType(SizingFixtures.SPICES, List.of());

        assertThat(cols).extracting(ImportColumn::key).doesNotContain("size_chart", "fit_note");
        assertThat(cols.stream().filter(c -> c.key().equals("option:Size")).findFirst().orElseThrow().kind())
                .isEqualTo(ColumnKind.ENUM);
    }

    @Test
    void theTemplateExplainsSizesAndCharts() {
        TemplateSpec spec = ImportTemplates.forType(SizingFixtures.DRESS, List.of(DRESSES));

        assertThat(spec.instructions()).anyMatch(l -> l.contains("type your own")).anyMatch(l -> l.contains("Size chart"));
        assertThat(ImportTemplates.forType(SizingFixtures.SPICES, List.of()).instructions()).noneMatch(l -> l.contains("Size chart"));
    }

    @Test
    void customSizesAndColoursAreAccepted() {
        SizeChart chart = SizingFixtures.chart(SizingFixtures.OWNER, "UK dresses",
                SizingFixtures.row("UK 12", "88", "90", "bust", "waist", "hip"),
                SizingFixtures.row("Free size", "90", "110", "bust", "waist", "hip"));
        ValidationResult res = validate(ctx(SizingFixtures.DRESS, chart), row("AWD", "AWD-12", "UK 12", "Ankara blue print", "UK dresses"),
                row("AWD", "AWD-F", "free size", "Indigo adire", ""));

        assertThat(res.rows()).allSatisfy(r -> assertThat(errors(r)).isEmpty());
        ImportedProduct p = res.products().getFirst();
        assertThat(p.variants()).extracting(v -> v.options().get("Size")).containsExactly("UK 12", "free size");
        assertThat(p.variants()).extracting(v -> v.options().get("Color"))
                .containsExactly("Ankara blue print", "Indigo adire");
        assertThat(p.sizeChartId()).isEqualTo(chart.id());
        assertThat(p.sizeChartName()).isEqualTo("UK dresses");
    }

    @Test
    void aSuggestionTakesTheCategorysSpellingAndCustomSpellingsAreUnifiedWithinAProduct() {
        SizeChart chart = SizingFixtures.chart(SizingFixtures.OWNER, "Mixed",
                SizingFixtures.row("XL", "100", "104", "bust", "waist", "hip"),
                SizingFixtures.row("Free size", "90", "110", "bust", "waist", "hip"));
        ValidationResult res = validate(ctx(SizingFixtures.DRESS, chart), row("AWD", "AWD-1", "xl", "Gold", "Mixed"),
                row("AWD", "AWD-2", "Free size", "Gold", ""), row("AWD", "AWD-3", "FREE SIZE", "Black", ""));

        assertThat(res.rows()).allSatisfy(r -> assertThat(errors(r)).isEmpty());
        assertThat(res.products().getFirst().variants()).extracting(v -> v.options().get("Size"))
                .containsExactly("XL", "Free size", "Free size");
    }

    @Test
    void customValuesAreStillCheckedForCharactersAndLength() {
        ValidationResult res = validate(ctx(SizingFixtures.DRESS), row("", "AWD-1", "<b>", "Gold", ""),
                row("", "AWD-2", "X".repeat(31), "Gold", ""));

        assertThat(errors(res.rows().get(0))).anyMatch(e -> e.startsWith("option:Size: Size: Use letters, digits"));
        assertThat(errors(res.rows().get(1))).contains("option:Size: Size: At most 30 characters");
    }

    @Test
    void aDimensionWithoutTheFlagStillOnlyAcceptsItsList() {
        List<String> header = List.of("SKU *", "Title *", "Brand *", "Image URLs *", "Price (USD) *", "Quantity *",
                "No barcode", "Variation", "Size");
        ImportContext c = new ImportContext(ImportMode.CREATE, SizingFixtures.SPICES, Map.of("grocery", SizingFixtures.SPICES),
                List.of(new ImportCategory(UUID.randomUUID(), "Groceries › Pantry › Spices", "grocery")), Set.of(),
                Set.of(), Map.of());
        ParsedSheet sheet = ImportFixtures.sheet(header, new String[] {"S-1", "Suya Pepper Spice Blend", "Kano", ImportFixtures.IMG,
            "5", "4", "Yes", "Size", "2 lb"});

        assertThat(errors(ImportValidator.validate(sheet, c).rows().getFirst()))
                .containsExactly("option:Size: Size: choose one of [8 oz, 1 lb]");
    }

    @Test
    void aChartNameThatIsNotTheSellersIsAnError() {
        ValidationResult res = validate(ctx(SizingFixtures.DRESS, SizingFixtures.dressChart(SizingFixtures.OWNER, "Adire dresses")),
                row("", "AWD-1", "M", "Gold", "Their dresses"));

        assertThat(errors(res.rows().getFirst())).singleElement().satisfies(e -> assertThat(e)
                .startsWith("size_chart: No size chart called \"Their dresses\" in your account"));
        assertThat(res.products()).isEmpty();
    }

    @Test
    void aChartOfAnotherSellerNeverResolvesEvenIfTheContextHoldsIt() {
        SizeChart foreign = SizingFixtures.dressChart("org_other", "Adire dresses");

        ValidationResult res = validate(ctx(SizingFixtures.DRESS, foreign), row("", "AWD-1", "M", "Gold", "Adire dresses"));

        assertThat(errors(res.rows().getFirst())).containsExactly("size_chart: Size chart not found");
    }

    @Test
    void aRequiredChartMissingIsAnError() {
        ValidationResult res = validate(ctx(SizingFixtures.DRESS_CHART_REQUIRED), row("", "AWD-1", "M", "Gold", ""));

        assertThat(errors(res.rows().getFirst())).singleElement().satisfies(e -> assertThat(e)
                .startsWith("size_chart: Add a size chart"));
    }

    @Test
    void anOptionalChartMayBeLeftOut() {
        ValidationResult res = validate(ctx(SizingFixtures.DRESS), row("", "AWD-1", "M", "Gold", ""));

        assertThat(errors(res.rows().getFirst())).isEmpty();
        assertThat(res.products().getFirst().sizeChartId()).isNull();
    }

    @Test
    void aSizeUsedByAVariantThatTheChartLacksIsAnErrorOnThatRow() {
        SizeChart chart = SizingFixtures.dressChart(SizingFixtures.OWNER, "Adire dresses");

        ValidationResult res = validate(ctx(SizingFixtures.DRESS, chart), row("AWD", "AWD-S", "S", "Gold", "Adire dresses"),
                row("AWD", "AWD-XL", "XL", "Gold", ""), row("AWD", "AWD-FREE", "Free size", "Gold", ""));

        assertThat(errors(res.rows().get(0))).singleElement().asString().contains("Another variant of this product has errors");
        assertThat(errors(res.rows().get(1))).singleElement().asString()
                .startsWith("option:Size: Size \"XL\" has no row in the size chart \"Adire dresses\"");
        assertThat(errors(res.rows().get(2))).singleElement().asString().contains("\"Free size\" has no row");
        assertThat(res.products()).isEmpty();
    }

    @Test
    void theChartNameMatchesIgnoringCaseAndTheFitNoteIsRead() {
        SizeChart chart = SizingFixtures.dressChart(SizingFixtures.OWNER, "Adire Dresses");
        Map<String, String> r = row("", "AWD-1", "M", "Gold", "adire dresses");
        r.put("Fit", "Runs small");

        ValidationResult res = validate(ctx(SizingFixtures.DRESS, chart), r);

        assertThat(errors(res.rows().getFirst())).isEmpty();
        assertThat(res.products().getFirst().fitNote()).isEqualTo(FitNote.RUNS_SMALL);
        assertThat(res.products().getFirst().sizeChartName()).isEqualTo("Adire Dresses");
    }

    @Test
    void anUnknownFitNoteIsAnError() {
        Map<String, String> r = row("", "AWD-1", "M", "Gold", "");
        r.put("Fit", "Snug");

        assertThat(errors(validate(ctx(SizingFixtures.DRESS), r).rows().getFirst()))
                .containsExactly("fit_note: Choose one of Runs small, True to size, Runs large");
    }

    @Test
    void aTypeWithoutSizingIgnoresTheChartColumns() {
        List<String> header = List.of("SKU *", "Title *", "Brand *", "Image URLs *", "Price (USD) *", "Quantity *",
                "No barcode", "Size chart", "Fit");
        ImportContext c = new ImportContext(ImportMode.CREATE, SizingFixtures.SPICES, Map.of("grocery", SizingFixtures.SPICES),
                List.of(new ImportCategory(UUID.randomUUID(), "Groceries › Pantry › Spices", "grocery")), Set.of(),
                Set.of(), Map.of());
        ParsedSheet sheet = ImportFixtures.sheet(header, new String[] {"S-1", "Suya Pepper Spice Blend", "Kano",
            ImportFixtures.IMG, "5", "4", "Yes", "My chart", "True to size"});

        ValidationResult res = ImportValidator.validate(sheet, c);

        assertThat(errors(res.rows().getFirst())).isEmpty();
        assertThat(res.products().getFirst().sizeChartId()).isNull();
        assertThat(res.fileWarnings()).singleElement().asString().contains("Ignored columns not in the template: Size chart, Fit");
    }

    @Test
    void moreCustomValuesThanOneProductMayCarryIsAnError() {
        SizeChart chart = SizingFixtures.dressChart(SizingFixtures.OWNER, "Adire dresses");
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i <= 41; i++) {
            rows.add(row("AWD", "AWD-" + i, "UK " + i, "Gold", i == 1 ? "Adire dresses" : ""));
        }

        ValidationResult res = ImportValidator.validate(new ParsedSheet(HEADER, rowsOf(rows)), ctx(SizingFixtures.DRESS, chart));

        assertThat(res.rows().stream().flatMap(r -> errors(r).stream()))
                .anyMatch(e -> e.contains("Up to 40 different size values of your own on one product"));
    }

    private static List<SheetRow> rowsOf(List<Map<String, String>> rows) {
        List<SheetRow> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> r = rows.get(i);
            out.add(new SheetRow(i + 2, HEADER.stream().map(h -> r.getOrDefault(h, "")).toList()));
        }
        return out;
    }
}
