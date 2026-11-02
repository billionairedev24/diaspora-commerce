package com.afrotransact.catalog.domain.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;

import com.afrotransact.catalog.domain.imports.ChartImportResult.ParsedChart;
import com.afrotransact.catalog.domain.sizing.ChartRow;
import com.afrotransact.catalog.domain.sizing.FitNote;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizingFixtures;
import org.junit.jupiter.api.Test;

/** The size chart upload: one row per size, every error case a seller can make, and the chart template. */
class ChartImportValidatorTest {

    private static final List<String> HEADER = List.of("Size chart name *", "Unit", "Size *", "Bust *", "Waist *",
            "Hip *", "Fit", "How to measure");

    private static final ChartImportContext DRESSES = new ChartImportContext("dress", MeasurementUnit.CM);

    private static ParsedSheet sheet(List<String> header, String[]... rows) {
        List<SheetRow> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            out.add(new SheetRow(i + 2, List.of(rows[i])));
        }
        return new ParsedSheet(header, out);
    }

    private static String[] row(String... cells) {
        return cells;
    }

    private static List<String> errors(ValidatedRow r) {
        return r.messages().stream().filter(m -> m.severity() == Severity.ERROR)
                .map(m -> m.column() + ": " + m.message()).toList();
    }

    @Test
    void aChartIsReadFromItsRows() {
        ChartImportResult res = ChartImportValidator.validate(sheet(HEADER,
                row("Adire dresses", "cm", "S", "84-88", "66-70", "92-96", "True to size", "Measure over light clothes"),
                row("Adire dresses", "", "M", "89-93", "71-75", "97-101", "", ""),
                row("Adire dresses", "", "UK 12", "94", "76,5", "102 cm", "", "")), DRESSES);

        assertThat(res.rows()).allSatisfy(r -> assertThat(r.status()).isEqualTo(RowStatus.VALID));
        assertThat(res.chartCount()).isEqualTo(1);
        ParsedChart chart = res.charts().getFirst();
        assertThat(chart.name()).isEqualTo("Adire dresses");
        assertThat(chart.rowNumbers()).containsExactly(2, 3, 4);
        assertThat(chart.content().unitSystem()).isEqualTo(MeasurementUnit.CM);
        assertThat(chart.content().productTypeCode()).isEqualTo("dress");
        assertThat(chart.content().fitNote()).isEqualTo(FitNote.TRUE_TO_SIZE);
        assertThat(chart.content().howToMeasure()).isEqualTo("Measure over light clothes");
        assertThat(chart.content().columns()).extracting(c -> c.key()).containsExactly("bust", "waist", "hip");
        assertThat(chart.content().rows()).extracting(ChartRow::size).containsExactly("S", "M", "UK 12");
        assertThat(chart.content().rows().getFirst().values().get("bust").min()).isEqualByComparingTo("84");
        assertThat(chart.content().rows().getFirst().values().get("bust").max()).isEqualByComparingTo("88");
        assertThat(chart.content().rows().get(2).values().get("waist").min()).isEqualByComparingTo("76.5");
        assertThat(chart.content().rows().get(2).values().get("hip").single()).isTrue();
    }

    @Test
    void sellersMayAddAndRenameMeasurementColumns() {
        ChartImportResult res = ChartImportValidator.validate(sheet(
                List.of("Size chart name", "Size", "Bust (cm)", "Shoulder width", "Sleeve"),
                row("Tops", "M", "90", "40", "58")), DRESSES);

        assertThat(res.charts().getFirst().content().columns()).extracting(c -> c.key(), c -> c.label())
                .containsExactly(tuple("bust", "Bust"),
                        tuple("shoulder_width", "Shoulder width"),
                        tuple("sleeve", "Sleeve"));
    }

    @Test
    void theUnitCanBeInchesAndDefaultsToTheTemplatesUnit() {
        ChartImportResult inches = ChartImportValidator.validate(sheet(HEADER,
                row("Inch chart", "inches", "M", "35", "28", "38", "", "")), DRESSES);
        ChartImportResult defaulted = ChartImportValidator.validate(sheet(HEADER,
                row("Plain chart", "", "M", "35", "28", "38", "", "")), new ChartImportContext(null, MeasurementUnit.IN));

        assertThat(inches.charts().getFirst().content().unitSystem()).isEqualTo(MeasurementUnit.IN);
        assertThat(defaulted.charts().getFirst().content().unitSystem()).isEqualTo(MeasurementUnit.IN);
        assertThat(defaulted.charts().getFirst().content().productTypeCode()).isNull();
    }

    @Test
    void aNonNumericMeasurementIsAnErrorOnItsCell() {
        ChartImportResult res = ChartImportValidator.validate(sheet(HEADER,
                row("Adire dresses", "cm", "S", "about 88", "66", "92", "", "")), DRESSES);

        ValidatedRow r = res.rows().getFirst();
        assertThat(errors(r)).containsExactly("m:bust: \"about 88\" is not a number or a range like 86-90");
        assertThat(r.messages().getFirst().columnIndex()).isEqualTo(3);
        assertThat(res.charts()).isEmpty();
    }

    @Test
    void theSmallestValueAboveTheLargestIsAnError() {
        ChartImportResult res = ChartImportValidator.validate(sheet(HEADER,
                row("Adire dresses", "cm", "S", "92-88", "66", "92", "", "")), DRESSES);

        assertThat(errors(res.rows().getFirst()))
                .containsExactly("m:bust: The smallest value (92) is above the largest (88)");
    }

    @Test
    void aMissingMeasurementIsAnError() {
        ChartImportResult res = ChartImportValidator.validate(sheet(HEADER,
                row("Adire dresses", "cm", "S", "88", "", "92", "", "")), DRESSES);

        assertThat(errors(res.rows().getFirst())).containsExactly("m:waist: Enter the waist of size S");
    }

    @Test
    void aDuplicateSizeInAChartIsAnErrorIgnoringCase() {
        ChartImportResult res = ChartImportValidator.validate(sheet(HEADER,
                row("Adire dresses", "cm", "XL", "100", "80", "104", "", ""),
                row("Adire dresses", "", "xl", "101", "81", "105", "", "")), DRESSES);

        assertThat(errors(res.rows().get(1))).singleElement().asString()
                .startsWith("size: Duplicate size xl: also on row 2");
        assertThat(errors(res.rows().getFirst())).singleElement().asString()
                .contains("Another row of this chart has errors (row 3); a chart is saved whole");
    }

    @Test
    void sizesNeedAValidLabel() {
        ChartImportResult res = ChartImportValidator.validate(sheet(HEADER,
                row("Adire dresses", "cm", "", "88", "66", "92", "", ""),
                row("Adire dresses", "", "M<b>", "88", "66", "92", "", "")), DRESSES);

        assertThat(errors(res.rows().get(0))).singleElement().asString().startsWith("size: Required");
        assertThat(errors(res.rows().get(1))).singleElement().asString().startsWith("size: Use letters, digits");
    }

    @Test
    void aMissingChartNameAndABadUnitOrFitNoteAreErrors() {
        ChartImportResult res = ChartImportValidator.validate(sheet(HEADER,
                row("", "cm", "S", "88", "66", "92", "", ""),
                row("Chart B", "yards", "S", "88", "66", "92", "Tight", "")), DRESSES);

        assertThat(errors(res.rows().get(0))).singleElement().asString().startsWith("chart_name: Required");
        assertThat(errors(res.rows().get(1))).containsExactly("unit: Write cm or in",
                "fit_note: Choose one of Runs small, True to size, Runs large");
    }

    @Test
    void aDifferentUnitOnALaterRowOfTheChartIsIgnoredWithAWarning() {
        ChartImportResult res = ChartImportValidator.validate(sheet(HEADER,
                row("Adire dresses", "cm", "S", "88", "66", "92", "", ""),
                row("Adire dresses", "in", "M", "92", "70", "96", "", "")), DRESSES);

        assertThat(res.rows().get(1).status()).isEqualTo(RowStatus.WARNING);
        assertThat(res.charts().getFirst().content().unitSystem()).isEqualTo(MeasurementUnit.CM);
    }

    @Test
    void oneBadChartDoesNotStopTheOthers() {
        ChartImportResult res = ChartImportValidator.validate(sheet(HEADER,
                row("Good chart", "cm", "S", "88", "66", "92", "", ""),
                row("Bad chart", "cm", "S", "x", "66", "92", "", "")), DRESSES);

        assertThat(res.chartCount()).isEqualTo(2);
        assertThat(res.charts()).extracting(ParsedChart::name).containsExactly("Good chart");
    }

    @Test
    void chartNamesAreGroupedIgnoringCase() {
        ChartImportResult res = ChartImportValidator.validate(sheet(HEADER,
                row("Adire dresses", "cm", "S", "88", "66", "92", "", ""),
                row("ADIRE DRESSES", "", "M", "92", "70", "96", "", "")), DRESSES);

        assertThat(res.chartCount()).isEqualTo(1);
        assertThat(res.charts().getFirst().content().rows()).hasSize(2);
    }

    @Test
    void aFileThatIsNotASizeChartSheetIsRejected() {
        assertThatThrownBy(() -> ChartImportValidator.validate(sheet(List.of("Size", "Bust"), row("M", "88")), DRESSES))
                .hasMessageContaining("Missing required column: Size chart name *");
        assertThatThrownBy(() -> ChartImportValidator.validate(sheet(List.of("Size chart name", "Size"),
                row("A", "M")), DRESSES)).hasMessageContaining("Add at least one measurement column");
        assertThatThrownBy(() -> ChartImportValidator.validate(sheet(HEADER), DRESSES))
                .hasMessageContaining("no size rows");
    }

    @Test
    void theTemplateOfASizedTypeStartsFromItsSuggestedMeasurements() {
        TemplateSpec spec = ChartImportColumns.template(SizingFixtures.DRESS);

        assertThat(spec.sheetName()).isEqualTo("Size charts");
        assertThat(spec.columns()).extracting(ImportColumn::label).containsExactly("Size chart name", "Unit", "Size",
                "Bust", "Waist", "Hip", "Fit", "How to measure");
        assertThat(spec.examples()).hasSize(3);
        assertThat(spec.examples().getFirst().get(2)).isEqualTo("XS");
        assertThat(spec.fileStem()).isEqualTo("afrotransact-size-charts-dress");
        ChartImportResult res = ChartImportValidator.validate(new ParsedSheet(
                spec.columns().stream().map(ImportColumn::header).toList(),
                List.of(new SheetRow(2, spec.examples().getFirst()), new SheetRow(3, spec.examples().get(1)),
                        new SheetRow(4, spec.examples().get(2)))), DRESSES);
        assertThat(res.rows()).allSatisfy(r -> assertThat(errors(r)).isEmpty());
        assertThat(res.charts()).hasSize(1);
    }

    @Test
    void theGenericTemplateHasExampleMeasurementsSellersCanReplace() {
        TemplateSpec spec = ChartImportColumns.template(null);

        assertThat(spec.columns()).extracting(ImportColumn::label).containsExactly("Size chart name", "Unit", "Size",
                "Chest", "Waist", "Fit", "How to measure");
        assertThat(spec.fileStem()).isEqualTo("afrotransact-size-charts");
    }
}
